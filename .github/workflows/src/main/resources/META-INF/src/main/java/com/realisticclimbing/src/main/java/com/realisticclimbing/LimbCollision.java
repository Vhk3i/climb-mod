package com.realisticclimbing;

import net.minecraft.world.phys.Vec3;

public class LimbCollision {
    private static final double LIMB_RADIUS = 0.15; 
    
    public static Vec3[] resolveCollisions(Vec3[] positions) {
        Vec3[] resolved = new Vec3[positions.length];
        System.arraycopy(positions, 0, resolved, 0, positions.length);
        double minDistance = LIMB_RADIUS * 2;

        for (int i = 0; i < resolved.length; i++) {
            for (int j = i + 1; j < resolved.length; j++) {
                Vec3 posA = resolved[i];
                Vec3 posB = resolved[j];
                Vec3 delta = posA.subtract(posB);
                double distance = delta.length();
                if (distance > 0 && distance < minDistance) {
                    double overlap = minDistance - distance;
                    Vec3 pushVector = delta.normalize().scale(overlap / 2.0);
                    resolved[i] = resolved[i].add(pushVector);
                    resolved[j] = resolved[j].subtract(pushVector);
                }
            }
        }
        return resolved;
    }
}
