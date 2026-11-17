package com.kaboom.explosionwork;

import java.util.ArrayList;

public class AdaptiveCube {
    // Координаты минимального угла куба в мире.
    private int x;
    private int y;
    private int z;

    // Длина ребра куба в блоках.
    private int size;

    // Энергия, находящаяся в процессе прохождения куба.
    private final ArrayList<EnergyPacket> packets = new ArrayList<>();

    private static final class EnergyPacket {

        // Энергия пакета.
        double energy;

        // Суммарный импульс.
        double momentX;
        double momentY;
        double momentZ;

        // Текущее среднее время пакета.
        double time;

        // Разброс энергии по времени (дисперсия).
        double timeVariance;

        // Пространственный центр энергии.
        double centerX;
        double centerY;
        double centerZ;

        // Пространственный разброс энергии (дисперсии).
        double varianceX;
        double varianceY;
        double varianceZ;   
    }
}
