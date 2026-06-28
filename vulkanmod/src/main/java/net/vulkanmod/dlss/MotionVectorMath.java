package net.vulkanmod.dlss;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * The camera-only (static-geometry) motion-vector reprojection — the exact math the GPU
 * motion-vector shader will run, kept here in pure Java so it can be validated headlessly
 * before (and independently of) the shader.
 *
 * <p>Given a pixel's UV and depth, reconstruct its world position using the inverse of the
 * current view-projection, reproject it with the previous view-projection, and return the
 * screen-space displacement to where that surface point was last frame.
 *
 * <p>Conventions (match this renderer): UV in [0,1] with v=0 at the top; depth in [0,1]
 * (near=0, far=1, NOT reversed-Z); clip NDC xy in [-1,1]. The returned vector is in UV space
 * (prevUV − curUV); it is scaled into Streamline's [-1,1] mvec range at tagging time.
 */
public final class MotionVectorMath {
    private MotionVectorMath() {}

    /** Reconstruct world-space position from a pixel (uv, depth) and the inverse current VP. */
    public static Vector4f reconstructWorld(float u, float v, float depth, Matrix4f invCurrentVP, Vector4f dst) {
        float ndcX = u * 2.0f - 1.0f;
        float ndcY = v * 2.0f - 1.0f;
        float ndcZ = depth;                 // Vulkan [0,1] depth
        dst.set(ndcX, ndcY, ndcZ, 1.0f);
        invCurrentVP.transform(dst);        // world (homogeneous)
        if (dst.w != 0.0f) dst.div(dst.w);
        dst.w = 1.0f;
        return dst;
    }

    /**
     * Camera-only motion vector for a pixel, in UV space: prevUV − curUV.
     * Static camera (prevVP == curVP) yields ~(0,0) for every pixel and depth.
     */
    public static Vector2f computeMotionVectorUV(float u, float v, float depth,
                                                 Matrix4f invCurrentVP, Matrix4f previousVP, Vector2f dst) {
        Vector4f world = reconstructWorld(u, v, depth, invCurrentVP, new Vector4f());
        Vector4f prevClip = previousVP.transform(world, new Vector4f());
        float pu = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
        float pv = (prevClip.y / prevClip.w) * 0.5f + 0.5f;
        return dst.set(pu - u, pv - v);
    }

    /** Project a world point through a view-projection to (uv, depth). Test/util helper. */
    public static void projectToScreen(Vector4f world, Matrix4f vp, float[] outUvDepth) {
        Vector4f clip = vp.transform(new Vector4f(world.x, world.y, world.z, 1.0f), new Vector4f());
        float invW = 1.0f / clip.w;
        outUvDepth[0] = (clip.x * invW) * 0.5f + 0.5f; // u
        outUvDepth[1] = (clip.y * invW) * 0.5f + 0.5f; // v
        outUvDepth[2] = clip.z * invW;                 // depth (already [0,1] for this projection)
    }
}
