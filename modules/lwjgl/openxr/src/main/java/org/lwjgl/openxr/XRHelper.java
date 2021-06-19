/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.openxr;

import org.lwjgl.system.*;

import java.nio.*;

import static org.lwjgl.openxr.XR10.*;

public class XRHelper {

    private static ByteBuffer mallocAndFillBufferStack(MemoryStack stack, int capacity, int sizeof, int type) {
        ByteBuffer b = stack.malloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    public static XrApiLayerProperties.Buffer prepareApiLayerProperties(MemoryStack stack, int numLayers) {
        return new XrApiLayerProperties.Buffer(
            mallocAndFillBufferStack(stack, numLayers, XrApiLayerProperties.SIZEOF, XR_TYPE_API_LAYER_PROPERTIES)
        );
    }

    public static XrExtensionProperties.Buffer prepareExtensionProperties(MemoryStack stack, int numExtensions) {
        return new XrExtensionProperties.Buffer(
            mallocAndFillBufferStack(stack, numExtensions, XrExtensionProperties.SIZEOF, XR_TYPE_EXTENSION_PROPERTIES)
        );
    }

    public static FloatBuffer createProjectionMatrixBuffer(
        MemoryStack stack, XrFovf fov, float nearZ, float farZ, boolean zZeroToOne
    ) {
        float tanLeft        = (float)Math.tan(fov.angleLeft());
        float tanRight       = (float)Math.tan(fov.angleRight());
        float tanDown        = (float)Math.tan(fov.angleDown());
        float tanUp          = (float)Math.tan(fov.angleUp());
        float tanAngleWidth  = tanRight - tanLeft;
        float tanAngleHeight;
        if (zZeroToOne) {
            tanAngleHeight = tanDown - tanUp;
        } else {
            tanAngleHeight = tanUp - tanDown;
        }

        FloatBuffer m = stack.mallocFloat(16);
        m.put(0, 2.0f / tanAngleWidth);
        m.put(4, 0.0f);
        m.put(8, (tanRight + tanLeft) / tanAngleWidth);
        m.put(12, 0.0f);

        m.put(1, 0.0f);
        m.put(5, 2.0f / tanAngleHeight);
        m.put(9, (tanUp + tanDown) / tanAngleHeight);
        m.put(13, 0.0f);

        m.put(2, 0.0f);
        m.put(6, 0.0f);
        if (zZeroToOne) {
            m.put(10, -farZ / (farZ - nearZ));
            m.put(14, -(farZ * nearZ) / (farZ - nearZ));
        } else {
            m.put(10, -(farZ + nearZ) / (farZ - nearZ));
            m.put(14, -(farZ * (nearZ + nearZ)) / (farZ - nearZ));
        }

        m.put(3, 0.0f);
        m.put(7, 0.0f);
        m.put(11, -1.0f);
        m.put(15, 0.0f);

        return m;
    }
}
