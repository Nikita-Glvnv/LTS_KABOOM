package com.kaboom.explosionwork;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class AdaptiveCube {
    private double airLoss;
    private double escapedEnergy;
    private double culledEnergy;


    private static final double MIN_ACTIVE_ENERGY = 1e-5;
    private static final double AIR_ATTENUATION = 0.15;
    private static final double LOBE_POWER = 2.2;
    private static final double SPREAD_FACTOR = 19.0;
    private static final int ATTENUATION_SAMPLES = 8;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double[][][] DIRECTIONS = createDirections();
    // Не обращается к WorldServer: получает данные только через receive().
    private final int x,y,z,size;
    private final int count;
    private final double[][] dirs;
    private final double[] weights;
    private final EnergyPacket scratch = new EnergyPacket();
    private static final int INPUT_STRIDE=5;
    private short[] inputOffset = new short[64];
    private double[] inputData = new double[64*INPUT_STRIDE];
    private int incomingCount;
    private int activePackets;
    // Очередь по физическим тикам: ближайшие 64 тика в кольце,
    // более дальние события — в overflow.
    private static final int RING_SIZE=64, RING_MASK=RING_SIZE-1;
    private final int[][] ring = new int[RING_SIZE][];
    private final int[] ringSizes = new int[RING_SIZE];
    private final Long2ObjectOpenHashMap<IntArrayList> overflow = new Long2ObjectOpenHashMap<>();
    private long previousTick=Long.MIN_VALUE;

    private long[] exitSource=new long[64],exitTarget=new long[64];
    private double[] exitEnergy=new double[64],exitMean=new double[64],exitSpread=new double[64],exitEarliest=new double[64];
    private byte[] exitDirection=new byte[64],exitPart=new byte[64];
    private int[] exitOwner=new int[64],freeExit=new int[64];
    private int allocatedExits,freeExitCount,pendingExitObjects;
    private int[] ownerRemaining=new int[64],freeOwner=new int[64];
    private int allocatedOwners,freeOwnerCount;
    private double preparedOutgoing;
    private final Diagnostics diagnostics=new Diagnostics();

    private static final class EnergyPacket {
        int ownerId;
        double energy,momentX,momentY,momentZ,time,timeVariance;
        double centerX,centerY,centerZ,varianceX,varianceY,varianceZ;
    }
    public AdaptiveCube(int x,int y,int z,int size){
        if(size<2||size>16||(size&(size-1))!=0)throw new IllegalArgumentException("Invalid cube size");
        if(((x|y|z)&(size-1))!=0)throw new IllegalArgumentException("Cube is not aligned");
        if(y<0||y+size>256)throw new IllegalArgumentException("Invalid cube height");
        this.x=x;this.y=y;this.z=z;this.size=size;
        count=size<=2?32:size<=4?64:size<=8?128:256;
        dirs=DIRECTIONS[Integer.numberOfTrailingZeros(count)-5];
        weights=new double[count];
    }
    public AdaptiveCube(oktoderevo.AirRegion region){this(region.x,region.y,region.z,region.size);}
    public void receive(int ex,int ey,int ez,double e,double mx,double my,double mz,double arrival){
        if(ex<x||ex>=x+size||ey<y||ey>=y+size||ez<z||ez>=z+size) throw new IllegalArgumentException("Entry outside cube");
        if(!Double.isFinite(e)||e<0||!Double.isFinite(mx)||!Double.isFinite(my)||!Double.isFinite(mz)||!Double.isFinite(arrival)||arrival<0)throw new IllegalArgumentException("Invalid energy transfer");
        if(e==0)return;
        if(incomingCount==inputOffset.length){int nc=inputOffset.length+(inputOffset.length>>1);inputOffset=Arrays.copyOf(inputOffset,nc);inputData=Arrays.copyOf(inputData,nc*INPUT_STRIDE);}
        int off=(ex-x)|((ey-y)<<4)|((ez-z)<<8);
        inputOffset[incomingCount]=(short)off;
        int p=incomingCount*INPUT_STRIDE;
        inputData[p]=e;inputData[p+1]=mx;inputData[p+2]=my;inputData[p+3]=mz;inputData[p+4]=arrival;
        incomingCount++;activePackets++;diagnostics.received++;diagnostics.receivedEnergy+=e;
    }
    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) y & 0xFFFL) << 26)
                | ((long) z & 0x3FFFFFFL);
    }

    private static double attenuationEnergy(double energy) {
        double e2 = energy * energy;
        return energy
                + 100.0 * e2
                / ((energy + 10.0) * (e2 + 0.01));
    }

    private static double sectorTransmittance(double energy, double length) {
        double step = length / ATTENUATION_SAMPLES;
        double integral = 0.0;
        for (int i = 0; i < ATTENUATION_SAMPLES; i++) {
            double distance = (i + 0.5) * step;
            double localEnergy = energy / (1.0 + SPREAD_FACTOR * distance * distance);
            double attenuation = attenuationEnergy(localEnergy);
            integral += step / Math.max(attenuation, 1e-12);
        }
        return Math.exp(-AIR_ATTENUATION * integral);
    }

    private static int expanded(int n){return Math.max(64,n+(n>>1));}
    private int newOwner(){
        if(freeOwnerCount!=0) return freeOwner[--freeOwnerCount];
        if(allocatedOwners==ownerRemaining.length){int n=expanded(allocatedOwners);ownerRemaining=Arrays.copyOf(ownerRemaining,n);freeOwner=Arrays.copyOf(freeOwner,n);}
        return allocatedOwners++;
    }
    private int newExit(){
        if(freeExitCount!=0)return freeExit[--freeExitCount];
        if(allocatedExits==exitSource.length){
            int n=expanded(allocatedExits);
            exitSource=Arrays.copyOf(exitSource,n);exitTarget=Arrays.copyOf(exitTarget,n);
            exitEnergy=Arrays.copyOf(exitEnergy,n);exitMean=Arrays.copyOf(exitMean,n);
            exitSpread=Arrays.copyOf(exitSpread,n);exitEarliest=Arrays.copyOf(exitEarliest,n);
            exitDirection=Arrays.copyOf(exitDirection,n);exitPart=Arrays.copyOf(exitPart,n);
            exitOwner=Arrays.copyOf(exitOwner,n);freeExit=Arrays.copyOf(freeExit,n);
        }
        return allocatedExits++;
    }
    private static int[] append(int[] a,int index,int value){
        if(a==null)a=new int[8];
        if(index==a.length)a=Arrays.copyOf(a,expanded(a.length));
        a[index]=value;return a;
    }
    private void schedule(int id,long now){
        int part=exitPart[id];
        double arrival=Math.max(exitEarliest[id],exitMean[id]+(part-1)*exitSpread[id]);
        long tick=(long)Math.floor(arrival);
        if(tick<now)tick=now;
        long diff=tick-now;
        if(diff<RING_SIZE){
            int slot=(int)(tick&RING_MASK);
            int n=ringSizes[slot];
            ring[slot]=append(ring[slot],n,id);
            ringSizes[slot]=n+1;
        } else {
            IntArrayList list=overflow.get(tick);
            if(list==null){list=new IntArrayList();overflow.put(tick,list);}
            list.add(id);
        }
    }
    private void addExit(EnergyPacket packet,long source,long target,double energy,int direction,double arrival,double spread){
        int id=newExit();
        exitSource[id]=source;exitTarget[id]=target;exitEnergy[id]=energy;
        exitDirection[id]=(byte)direction;exitPart[id]=0;
        exitMean[id]=arrival;exitSpread[id]=spread;exitEarliest[id]=Math.floor(packet.time)+1.0;
        exitOwner[id]=packet.ownerId;
        ownerRemaining[packet.ownerId]++;
        pendingExitObjects++;preparedOutgoing+=energy;
        diagnostics.exitsCreated++;
        schedule(id,previousTick);
    }
    private static int clampCell(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private double keepInside(double v,int min){return Math.max(min+1e-6,Math.min(min+size-1e-6,v));}
    private void appendFaceExits(EnergyPacket packet,int face,double hx,double hy,double hz,double ux,double uy,double uz,double distance,double energy,int direction){
        double ca=face==0?hy:hx, cb=face==2?hy:hz;
        int minA=face==0?y:x,minB=face==2?y:z;
        double fa=ca-.5,fb=cb-.5;
        int a0=(int)Math.floor(fa),b0=(int)Math.floor(fb);
        double aw=fa-a0,bw=fb-b0;
        int normalMin=face==0?x:face==1?y:z;
        double component=face==0?ux:face==1?uy:uz;
        int ni=component>0?normalMin+size-1:normalMin;
        int no=component>0?normalMin+size:normalMin-1;
        double arrival=packet.time+.5+1.35*distance;
        double spread=Math.sqrt(2.0*Math.max(0,packet.timeVariance+.06*distance));
        for(int ai=0;ai<2;ai++)for(int bi=0;bi<2;bi++){
            double w=(ai==0?1-aw:aw)*(bi==0?1-bw:bw);
            double e=energy*w;
            if(e<MIN_ACTIVE_ENERGY)continue;
            int ia=clampCell(a0+ai,minA,minA+size-1);
            int ib=clampCell(b0+bi,minB,minB+size-1);
            int sx=face==0?ni:ia,sy=face==1?ni:face==0?ia:ib,sz=face==2?ni:ib;
            int tx=face==0?no:ia,ty=face==1?no:face==0?ia:ib,tz=face==2?no:ib;
            if(ty<0||ty>=256||tx<-30000000||tx>30000000||tz<-30000000||tz>30000000){
                escapedEnergy+=e;diagnostics.escapedParts++;diagnostics.escapedEnergy+=e;continue;
            }
            addExit(packet,pack(sx,sy,sz),pack(tx,ty,tz),e,direction,arrival,spread);
        }
    }
    private void appendOrigin(EnergyPacket packet,double ox,double oy,double oz,double originWeight,double totalWeight){
        ox=keepInside(ox,x);oy=keepInside(oy,y);oz=keepInside(oz,z);
        diagnostics.origins++;diagnostics.directions+=count;
        for(int i=0;i<count;i++){
            double energy=packet.energy*originWeight*weights[i]/totalWeight;
            if(energy<MIN_ACTIVE_ENERGY)continue;
            double ux=dirs[i][0],uy=dirs[i][1],uz=dirs[i][2];
            double tx=ux>1e-12?(x+size-ox)/ux:ux< -1e-12?(x-ox)/ux:Double.POSITIVE_INFINITY;
            double ty=uy>1e-12?(y+size-oy)/uy:uy< -1e-12?(y-oy)/uy:Double.POSITIVE_INFINITY;
            double tz=uz>1e-12?(z+size-oz)/uz:uz< -1e-12?(z-oz)/uz:Double.POSITIVE_INFINITY;
            int face=tx<=ty&&tx<=tz?0:ty<=tz?1:2;
            double distance=Math.min(tx,Math.min(ty,tz));
            if(!Double.isFinite(distance)||distance<=0)continue;
            diagnostics.attenuationCalls++;
            energy*=sectorTransmittance(packet.energy,distance);
            if(energy<MIN_ACTIVE_ENERGY)continue;
            appendFaceExits(packet,face,ox+ux*distance,oy+uy*distance,oz+uz*distance,ux,uy,uz,distance,energy,i);
        }
    }
    private void prepare(EnergyPacket packet){
        double px=packet.momentX,py=packet.momentY,pz=packet.momentZ;
        double m=Math.sqrt(px*px+py*py+pz*pz);
        double k=Math.min(1.0,m/packet.energy);
        double vx=m>1e-15?px/m:0,vy=m>1e-15?py/m:0,vz=m>1e-15?pz/m:0;
        double total=0,scale=2.0*(LOBE_POWER+1.0)*k;
        for(int i=0;i<count;i++){
            double[] dir=dirs[i];double dot=dir[0]*vx+dir[1]*vy+dir[2]*vz;
            double directed=dot>0&&k!=0?scale*Math.pow(dot,LOBE_POWER):0.0;
            double diffuse=1.0-k;
            weights[i]=diffuse+directed;total+=weights[i];
        }
        if(total<=0)return;
        double sx=Math.sqrt(Math.max(0,packet.varianceX)),sy=Math.sqrt(Math.max(0,packet.varianceY)),sz=Math.sqrt(Math.max(0,packet.varianceZ));
        if(sx+sy+sz<1e-6){appendOrigin(packet,packet.centerX,packet.centerY,packet.centerZ,1,total);return;}
        appendOrigin(packet,packet.centerX+sx,packet.centerY+sy,packet.centerZ+sz,.25,total);
        appendOrigin(packet,packet.centerX+sx,packet.centerY-sy,packet.centerZ-sz,.25,total);
        appendOrigin(packet,packet.centerX-sx,packet.centerY+sy,packet.centerZ-sz,.25,total);
        appendOrigin(packet,packet.centerX-sx,packet.centerY-sy,packet.centerZ+sz,.25,total);
    }
    @FunctionalInterface public interface EnergyOutput{void emit(long source,long target,double energy,double mx,double my,double mz,double arrival);}
    private void emitDue(int id,long tick,EnergyOutput output){
        diagnostics.exitsChecked++;
        int part=exitPart[id];
        while(part<3){
            double arrival=Math.max(exitEarliest[id],exitMean[id]+(part-1)*exitSpread[id]);
            if(Math.floor(arrival)>tick){exitPart[id]=(byte)part;schedule(id,tick);return;}
            double energy=exitEnergy[id]*(part==1?.5:.25);
            if(energy>=MIN_ACTIVE_ENERGY){
                double[] dir=dirs[exitDirection[id]&255];
                output.emit(exitSource[id],exitTarget[id],energy,energy*dir[0],energy*dir[1],energy*dir[2],Math.max(arrival,(double)tick));
                diagnostics.emittedParts++;diagnostics.emittedEnergy+=energy;
            } else {culledEnergy+=energy;diagnostics.culledParts++;diagnostics.culledEnergy+=energy;}
            part++;
        }
        pendingExitObjects--;
        freeExit[freeExitCount++]=id;
        int owner=exitOwner[id];
        if(--ownerRemaining[owner]==0){
            freeOwner[freeOwnerCount++]=owner;
            activePackets--;diagnostics.completed++;
        }
    }
    public void process(long tick, EnergyOutput output){
        if(tick<0||output==null)throw new IllegalArgumentException("Invalid process args");
        if(previousTick!=Long.MIN_VALUE&&tick!=previousTick+1)throw new IllegalStateException("AdaptiveCube requires consecutive ticks");
        previousTick=tick;
        for(int i=incomingCount-1;i>=0;i--){
            int p=i*INPUT_STRIDE,off=inputOffset[i]&65535;
            scratch.energy=inputData[p];scratch.momentX=inputData[p+1];scratch.momentY=inputData[p+2];scratch.momentZ=inputData[p+3];
            scratch.time=inputData[p+4];scratch.timeVariance=0;
            scratch.centerX=x+(off&15)+.5;scratch.centerY=y+((off>>4)&15)+.5;scratch.centerZ=z+((off>>8)&15)+.5;
            scratch.varianceX=scratch.varianceY=scratch.varianceZ=0;
            scratch.ownerId=newOwner();ownerRemaining[scratch.ownerId]=0;preparedOutgoing=0;
            double before=escapedEnergy;
            long start=System.nanoTime();prepare(scratch);long nanos=System.nanoTime()-start;
            diagnostics.prepared++;diagnostics.prepareNs+=nanos;
            diagnostics.maxPrepareNs=Math.max(diagnostics.maxPrepareNs,nanos);
            diagnostics.generatedEnergy+=preparedOutgoing;
            diagnostics.maxExitsPerPacket=Math.max(diagnostics.maxExitsPerPacket,ownerRemaining[scratch.ownerId]);
            airLoss+=Math.max(0,scratch.energy-preparedOutgoing-(escapedEnergy-before));
            if(ownerRemaining[scratch.ownerId]==0){freeOwner[freeOwnerCount++]=scratch.ownerId;activePackets--;diagnostics.completed++;}
        }
        incomingCount=0;
        int slot=(int)(tick&RING_MASK);
        int n=ringSizes[slot];
        ringSizes[slot]=0;
        int[] due=ring[slot];
        for(int i=0;i<n;i++)emitDue(due[i],tick,output);
        IntArrayList far=overflow.remove(tick);
        if(far!=null)for(int i=0;i<far.size();i++)emitDue(far.getInt(i),tick,output);
    }
    public boolean hasPending(){return activePackets!=0;}
    public int getPacketCount(){return activePackets;}
    public int getPendingExitCount(){return pendingExitObjects;}
    public void drainDiagnostics(Diagnostics target){target.add(diagnostics);diagnostics.reset();}
    public double takeAirLoss(){double x=airLoss;airLoss=0;return x;}
    public double takeEscapedEnergy(){double x=escapedEnergy;escapedEnergy=0;return x;}
    public double takeCulledEnergy(){double x=culledEnergy;culledEnergy=0;return x;}
    public void clear() {
        incomingCount = 0;
        activePackets = 0;
        allocatedOwners = freeOwnerCount = 0;
        allocatedExits = freeExitCount = 0;
        pendingExitObjects = 0;
        preparedOutgoing = 0.0;
        Arrays.fill(ringSizes, 0);
        overflow.clear();
        previousTick = Long.MIN_VALUE;
        airLoss = escapedEnergy = culledEnergy = 0.0;
        diagnostics.reset();
    }
    public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}public int getSize(){return size;}
    private static double[][][] createDirections() {
        double[][][] tables = new double[4][][];
        for (int level = 0; level < 4; level++) {
            int count = 32 << level;
            int half = count / 2;
            double[][] directions = new double[count][3];
            for (int i = 0; i < half; i++) {
                double y = 1.0 - 2.0 * (i + 0.5) / half;
                double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double angle = i * GOLDEN_ANGLE;
                double dx = r * Math.cos(angle);
                double dz = r * Math.sin(angle);
                directions[i][0] = dx;
                directions[i][1] = y;
                directions[i][2] = dz;
                directions[i + half][0] = -dx;
                directions[i + half][1] = -y;
                directions[i + half][2] = -dz;
            }
            tables[level] = directions;
        }
        return tables;
    }

    public static final class Diagnostics {
        public long received;
        public long prepared;
        public long completed;
        public long origins;
        public long directions;
        public long attenuationCalls;
        public long exitsCreated;
        public long exitsChecked;
        public long exitsWaiting;
        public long emittedParts;
        public long culledParts;
        public long escapedParts;
        public long prepareNs;
        public long maxPrepareNs;
        public long maxExitsPerPacket;
        public double receivedEnergy;
        public double generatedEnergy;
        public double emittedEnergy;
        public double culledEnergy;
        public double escapedEnergy;
        public void add(Diagnostics other) {
            received += other.received;
            prepared += other.prepared;
            completed += other.completed;
            origins += other.origins;
            directions += other.directions;
            attenuationCalls += other.attenuationCalls;
            exitsCreated += other.exitsCreated;
            exitsChecked += other.exitsChecked;
            exitsWaiting += other.exitsWaiting;
            emittedParts += other.emittedParts;
            culledParts += other.culledParts;
            escapedParts += other.escapedParts;
            prepareNs += other.prepareNs;
            maxPrepareNs = Math.max(maxPrepareNs, other.maxPrepareNs);
            maxExitsPerPacket = Math.max(maxExitsPerPacket, other.maxExitsPerPacket);
            receivedEnergy += other.receivedEnergy;
            generatedEnergy += other.generatedEnergy;
            emittedEnergy += other.emittedEnergy;
            culledEnergy += other.culledEnergy;
            escapedEnergy += other.escapedEnergy;
        }
        public void reset() {
            received = prepared = completed = 0;
            origins = directions = attenuationCalls = 0;
            exitsCreated = exitsChecked = exitsWaiting = 0;
            emittedParts = culledParts = escapedParts = 0;
            prepareNs = maxPrepareNs = maxExitsPerPacket = 0;
            receivedEnergy = generatedEnergy = 0.0;
            emittedEnergy = culledEnergy = escapedEnergy = 0.0;
        }
    }
}
