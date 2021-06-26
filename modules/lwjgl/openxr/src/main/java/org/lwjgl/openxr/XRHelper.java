/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.openxr;

import org.lwjgl.glfw.*;
import org.lwjgl.system.*;
import org.lwjgl.system.windows.*;

import java.nio.*;

import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A helper class with some static methods to help applications with OpenXR related tasks that are cumbersome in
 * some way.
 */
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

    /**
     * Allocates an {@link XrApiLayerProperties.Buffer} onto the given stack with the given number of layers and
     * sets the type of each element in the Buffer to XR_TYPE_API_LAYER_PROPERTIES.
     *
     * Note: you can't use the Buffer after the stack is gone!
     * @param stack The stack to allocate the Buffer on
     * @param numLayers The number of elements the Buffer should get
     * @return The created Buffer
     */
    public static XrApiLayerProperties.Buffer prepareApiLayerProperties(MemoryStack stack, int numLayers) {
        return new XrApiLayerProperties.Buffer(
            mallocAndFillBufferStack(stack, numLayers, XrApiLayerProperties.SIZEOF, XR_TYPE_API_LAYER_PROPERTIES)
        );
    }

    /**
     * Allocates an {@link XrExtensionProperties.Buffer} onto the given stack with the given number of extensions
     * and sets the type of each element in the Buffer to XR_TYPE_EXTENSION_PROPERTIES.
     *
     * Note: you can't use the Buffer after the stack is gone!
     * @param stack The stack onto which to allocate the Buffer
     * @param numExtensions The number of elements the Buffer should get
     * @return The created Buffer
     */
    public static XrExtensionProperties.Buffer prepareExtensionProperties(MemoryStack stack, int numExtensions) {
        return new XrExtensionProperties.Buffer(
            mallocAndFillBufferStack(stack, numExtensions, XrExtensionProperties.SIZEOF, XR_TYPE_EXTENSION_PROPERTIES)
        );
    }

    /**
     * Allocates a {@link FloatBuffer} onto the given stack and fills it such that it can be used as parameter
     * to the <b>set</b> method of <b>Matrix4f</b>. The buffer will be filled such that it represents a projection
     * matrix with the given <b>fov</b>, <b>nearZ</b> (a.k.a. near plane), <b>farZ</b> (a.k.a. far plane).
     * @param stack The stack onto which the buffer should be allocated
     * @param fov The desired Field of View for the projection matrix. You should normally use the value of
     *            {@link XrCompositionLayerProjectionView#fov}.
     * @param nearZ The nearest Z value that the user should see (also known as the near plane)
     * @param farZ The furthest Z value that the user should see (also known as far plane)
     * @param zZeroToOne True if the z-axis of the coordinate system goes from 0 to 1 (Vulkan).
     *                   False if the z-axis of the coordinate system goes from -1 to 1 (OpenGL).
     * @return A {@link FloatBuffer} that contains the matrix data of the desired projection matrix. Use the
     * <b>set</b> method of a <b>Matrix4f</b> instance to copy the buffer values to that matrix.
     */
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

    /**
     * Allocates a <b>XrGraphicsBindingOpenGL**</b> struct for the current platform onto the given stack. It should
     * be included in the next-chain of the {@link XrSessionCreateInfo} that will be used to create an
     * OpenXR session with OpenGL rendering. (Every platform requires a different OpenGL graphics binding
     * struct, so this method spares users the trouble of working with all these cases themselves.)
     *
     * Note: {@link XR10#xrCreateSession} must be called <b>before</b> the given stack is dropped!
     * @param stack The stack onto which to allocate the graphics binding struct
     * @param window The GLFW window handle used to create the OpenGL context
     * @return A XrGraphicsBindingOpenGL** struct that can be used to create a session
     * @throws IllegalStateException If the current platform is not supported
     */
    public static Struct createGraphicsBindingOpenGL(MemoryStack stack, long window) throws IllegalStateException {
        switch (Platform.get()) {
            case LINUX:
//                    if (xlib) { TODO
//                        XrGraphicsBindingOpenGLXlibKHR graphicsBinding = XrGraphicsBindingOpenGLXlibKHR.malloc();
//                        graphicsBinding.set(
//                            KHROpenglEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_XLIB_KHR,
//                            NULL,
//                            GLFWNativeX11.glfwGetX11Display(),
//                            ,
//                            ,
//                            GLX.glXGetCurrentDrawable(),
//                            GLFWNativeGLX.glfwGetGLXContext(window)
//                        );
//                        this.graphicsBinding = graphicsBinding;
//                    } else if (wayland) {
//                        XrGraphicsBindingOpenGLWaylandKHR graphicsBinding = XrGraphicsBindingOpenGLWaylandKHR.malloc();
//                        graphicsBinding.set(
//                            KHROpenglEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_WAYLAND_KHR,
//                            NULL,
//                            GLFWNativeWayland.glfwGetWaylandDisplay()
//                        );
//                        this.graphicsBinding = graphicsBinding;
//                    } else {
//                        throw new IllegalStateException();
//                    }
                throw new IllegalStateException("Linux support is work in progress");
            case WINDOWS:
                XrGraphicsBindingOpenGLWin32KHR winGraphicsBinding = XrGraphicsBindingOpenGLWin32KHR.mallocStack(stack);
                winGraphicsBinding.set(
                    KHROpenglEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_WIN32_KHR,
                    NULL,
                    User32.GetDC(GLFWNativeWin32.glfwGetWin32Window(window)),
                    GLFWNativeWGL.glfwGetWGLContext(window)
                );
                return winGraphicsBinding;
            default:
                throw new IllegalStateException("Unsupported operation system: " + Platform.get());
        }
    }
}
