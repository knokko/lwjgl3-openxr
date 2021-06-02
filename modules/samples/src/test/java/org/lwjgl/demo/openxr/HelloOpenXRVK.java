/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.openxr;

import org.lwjgl.*;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;

import static org.lwjgl.demo.openxr.HelloOpenXR.*;
import static org.lwjgl.openxr.EXTDebugUtils.*;
import static org.lwjgl.openxr.KHRVulkanEnable.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class HelloOpenXRVK {

    private static final String VK_LAYER_LUNARG_STANDARD_VALIDATION = "VK_LAYER_LUNARG_standard_validation";

    private static final int VERTEX_SIZE = 2 * 3 * 4;
    private static final int VERTEX_LOCATION_POSITION = 0;
    private static final int VERTEX_LOCATION_COLOR = 1;
    private static final int VERTEX_OFFSET_POSITION = 0;
    private static final int VERTEX_OFFSET_COLOR = 1 * 3 * 4;

    private static class SwapchainWrapper {

        final XrSwapchain swapchain;
        final int width, height, numSamples;
        final long[] images;

        SwapchainWrapper(XrSwapchain swapchain, int width, int height, int numSamples, long[] images) {
            if (swapchain == null) throw new NullPointerException("swapchain");
            this.swapchain = swapchain;
            if (width <= 0) throw new IllegalArgumentException("width is " + width);
            this.width = width;
            if (height <= 0) throw new IllegalArgumentException("height is " + height);
            this.height = height;
            if (images == null) throw new IllegalArgumentException("images are null");
            if (numSamples <= 0) throw new IllegalArgumentException("numSamples is " + numSamples);
            this.numSamples = numSamples;
            for (long image : images) {
                if (image == 0) {
                    throw new IllegalArgumentException("An image is 0");
                }
            }
            this.images = images;
        }
    }

    private static ByteBuffer extractByteBuffer(String resourceName) {
        try (
            InputStream rawInput = HelloOpenXRVK.class.getClassLoader().getResourceAsStream(resourceName);
            InputStream input = new BufferedInputStream(Objects.requireNonNull(rawInput))
        ) {
            List<Byte> byteList = new ArrayList<>(input.available());
            int nextByte = input.read();
            while (nextByte != -1) {
                byteList.add((byte) nextByte);
                nextByte = input.read();
            }

            ByteBuffer result = memCalloc(byteList.size());
            for (int index = 0; index < byteList.size(); index++) {
                result.put(index, byteList.get(index));
            }
            return result;
        } catch (IOException shouldntHappen) {
            throw new Error("Needed resources should always be available", shouldntHappen);
        }
    }

    private static ByteBuffer getVertexShaderBytes() {
        return extractByteBuffer("demo/openxr/vulkan/hello.vert.spv");
    }

    private static ByteBuffer getFragmentShaderBytes() {
        return extractByteBuffer("demo/openxr/vulkan/hello.frag.spv");
    }

    public static void main(String[] args) {
        XR.create();

        HelloOpenXRVK classInstance = new HelloOpenXRVK();
        classInstance.start();
    }

    private XrInstance xrInstance;
    private XrDebugUtilsMessengerEXT xrDebugMessenger;
    private long xrSystemId;

    private XrSession xrVkSession;
    private int xrSessionState;
    private boolean missingXrDebug;

    private VkInstance vkInstance;
    private long vkDebugMessenger;
    private VkPhysicalDevice vkPhysicalDevice;
    private VkDevice vkDevice;
    private VkQueue vkQueue;
    private int vkQueueFamilyIndex;
    private int vkQueueIndex;
    private long vkCommandPool;
    private long vkRenderPass;
    private long vkPipelineLayout;
    private long[] vkGraphicsPipelines;

    private SwapchainWrapper[] swapchains;
    private int viewConfiguration;
    private int swapchainFormat;

    private XrSpace renderSpace;

    private void start() {
        try {
            createXrInstance();
            initXrSystem();
            initVk();
            createXrVkSession();
            createRenderResources();
            loopXrSession();
        } catch (RuntimeException ex) {
            System.err.println("OpenXR testing failed:");
            ex.printStackTrace();
        }

        // Always clean up
        destroyRenderResources();
        destroyXrVkSession();
        destroyVk();
        destroyXrInstance();
    }

    private void createXrInstance() {
        try (MemoryStack stack = stackPush()) {

            boolean hasCoreValidationLayer = false;
            IntBuffer pNumLayers = stack.callocInt(1);
            xrEnumerateApiLayerProperties(pNumLayers, null);
            int numLayers = pNumLayers.get(0);
            XrApiLayerProperties.Buffer pLayers = new XrApiLayerProperties.Buffer(
                mallocAndFillBufferStack(numLayers, XrApiLayerProperties.SIZEOF, XR_TYPE_API_LAYER_PROPERTIES)
            );
            xrCheck(xrEnumerateApiLayerProperties(pNumLayers, pLayers), "EnumerateApiLayerProperties");
            System.out.println(numLayers + " XR layers are available:");
            for (int index = 0; index < numLayers; index++) {
                XrApiLayerProperties layer = pLayers.get(index);
                System.out.println(layer.layerNameString());
                if (layer.layerNameString().equals("XR_APILAYER_LUNARG_core_validation")) {
                    hasCoreValidationLayer = true;
                }
            }
            System.out.println("-----------");

            IntBuffer pNumExtensions = stack.mallocInt(1);
            XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer)null, pNumExtensions, null);
            int numExtensions = pNumExtensions.get(0);

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                mallocAndFillBufferStack(numExtensions, XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            xrCheck(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer)null, pNumExtensions, properties), "EnumerateInstanceExtensionProperties");

            System.out.printf("OpenXR loaded with %d extensions:%n", numExtensions);
            System.out.println("~~~~~~~~~~~~~~~~~~");
            PointerBuffer extensions    = stack.mallocPointer(numExtensions);
            boolean missingVulkan = true;
            missingXrDebug = true;
            while (properties.hasRemaining()) {
                XrExtensionProperties prop          = properties.get();
                String                extensionName = prop.extensionNameString();
                System.out.println(extensionName);
                extensions.put(memASCII(extensionName));
                if (extensionName.equals(KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME)) {
                    missingVulkan = false;
                }
                if (extensionName.equals("XR_EXT_debug_utils")) {
                    missingXrDebug = false;
                }
            }
            extensions.rewind();
            System.out.println("~~~~~~~~~~~~~~~~~~");

            if (missingVulkan) {
                throw new IllegalStateException("OpenXR library does not provide required extension: " + KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME);
            }

            XrApplicationInfo applicationInfo = XrApplicationInfo.mallocStack();
            applicationInfo.apiVersion(XR10.XR_CURRENT_API_VERSION);
            applicationInfo.applicationName(stack.UTF8("DummyXRVK"));

            PointerBuffer wantedExtensions;
            if (missingXrDebug) {
                wantedExtensions = stack.callocPointer(1);
            } else {
                wantedExtensions = stack.callocPointer(2);
                wantedExtensions.put(1, stack.UTF8("XR_EXT_debug_utils"));
                System.out.println("Enabling XR debug utils");
            }
            wantedExtensions.put(0, stack.UTF8(XR_KHR_VULKAN_ENABLE_EXTENSION_NAME));

            PointerBuffer wantedLayers;
            if (hasCoreValidationLayer) {
                wantedLayers = stack.callocPointer(1);
                wantedLayers.put(0, stack.UTF8("XR_APILAYER_LUNARG_core_validation"));
                System.out.println("Enabling XR core validation");
            } else {
                wantedLayers = null;
            }

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.mallocStack();
            createInfo.set(
                XR10.XR_TYPE_INSTANCE_CREATE_INFO,
                0,
                0,
                applicationInfo,
                wantedLayers,
                wantedExtensions
            );

            PointerBuffer instancePtr = stack.mallocPointer(1);
            xrCheck(XR10.xrCreateInstance(createInfo, instancePtr), "CreateInstance");
            xrInstance = new XrInstance(instancePtr.get(0), createInfo);
        }
    }

    private void initXrSystem() {
        try (MemoryStack stack = stackPush()) {

            XrSystemGetInfo giSystem = XrSystemGetInfo.callocStack(stack);
            giSystem.type(XR_TYPE_SYSTEM_GET_INFO);
            giSystem.formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);

            LongBuffer pSystemId = stack.callocLong(1);
            xrCheck(xrGetSystem(xrInstance, giSystem, pSystemId), "GetSystem");
            xrSystemId = pSystemId.get(0);

            System.out.println("System ID is " + xrSystemId);
        }
    }

    private void initVk() {
        try (MemoryStack stack = stackPush()) {

            XrGraphicsRequirementsVulkanKHR graphicsRequirements = XrGraphicsRequirementsVulkanKHR.callocStack(stack);
            graphicsRequirements.type(XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR);
            xrCheck(xrGetVulkanGraphicsRequirementsKHR(xrInstance, xrSystemId, graphicsRequirements), "GetVulkanGraphicsRequirements");
            long minApiVersion = graphicsRequirements.minApiVersionSupported();
            long maxApiVersion = graphicsRequirements.maxApiVersionSupported();
            long minVkMajor = XR_VERSION_MAJOR(minApiVersion);
            long minVkMinor = XR_VERSION_MINOR(minApiVersion);
            long minVkPatch = XR_VERSION_PATCH(minApiVersion);
            System.out.println("Minimum Vulkan API version: " + minVkMajor + "." + minVkMinor + "." + minVkPatch);
            System.out.println("Maximum Vulkan API version: " + XR_VERSION_MAJOR(maxApiVersion) + "." + XR_VERSION_MINOR(maxApiVersion) + "." + XR_VERSION_PATCH(maxApiVersion));

            VkApplicationInfo appInfo = VkApplicationInfo.callocStack(stack);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.apiVersion(VK_MAKE_VERSION((int) minVkMajor, (int) minVkMinor, (int) minVkPatch));
            appInfo.pApplicationName(stack.UTF8("DummyXRVK"));
            appInfo.applicationVersion(VK_MAKE_VERSION(0, 1, 0));

            VkInstanceCreateInfo ciInstance = VkInstanceCreateInfo.callocStack(stack);
            ciInstance.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            ciInstance.pApplicationInfo(appInfo);

            IntBuffer pCapXrVkInstanceExtensions = stack.callocInt(1);
            xrGetVulkanInstanceExtensionsKHR(xrInstance, xrSystemId, pCapXrVkInstanceExtensions, null);

            ByteBuffer pXrVkInstanceExtensions = stack.calloc(pCapXrVkInstanceExtensions.get(0));
            xrCheck(xrGetVulkanInstanceExtensionsKHR(xrInstance, xrSystemId, pCapXrVkInstanceExtensions, pXrVkInstanceExtensions), "GetVulkanInstanceExtensions");
            String[] xrVkInstanceExtensions = memUTF8(pXrVkInstanceExtensions).split(" ");
            if (xrVkInstanceExtensions.length > 0) {
                String lastXrVkExtension = xrVkInstanceExtensions[xrVkInstanceExtensions.length - 1];
                int lastCharId = lastXrVkExtension.charAt(lastXrVkExtension.length() - 1);

                // It looks like the last string is null-terminated, which causes problems...
                if (lastCharId == 0) {
                    xrVkInstanceExtensions[xrVkInstanceExtensions.length - 1] = lastXrVkExtension.substring(0, lastXrVkExtension.length() - 1);
                }
            }

            System.out.println(xrVkInstanceExtensions.length + " Vulkan instance extensions are required for OpenXR:");
            for (String xrVkExtension : xrVkInstanceExtensions) {
                System.out.println(xrVkExtension);
            }
            System.out.println("--------------");

            IntBuffer pNumExtensions = stack.callocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pNumExtensions, null);
            int numExtensions = pNumExtensions.get(0);

            VkExtensionProperties.Buffer pExtensionProps = VkExtensionProperties.callocStack(numExtensions, stack);
            vkCheck(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pNumExtensions, pExtensionProps), "EnumerateInstanceExtensionProperties");

            boolean hasExtDebugUtils = false;
            boolean[] hasXrVkInstanceExtensions = new boolean[xrVkInstanceExtensions.length];
            System.out.println("Available Vulkan instance extensions:");
            for (int index = 0; index < numExtensions; index++) {
                VkExtensionProperties extensionProp = pExtensionProps.get(index);
                String extensionName = extensionProp.extensionNameString();
                System.out.println(extensionName);
                if (extensionName.equals(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    hasExtDebugUtils = true;
                }

                // Check if all required Vulkan extensions for OpenXR are present
                for (int xrExtIndex = 0; xrExtIndex < hasXrVkInstanceExtensions.length; xrExtIndex++) {
                    if (!hasXrVkInstanceExtensions[xrExtIndex]) {
                        if (extensionName.equals(xrVkInstanceExtensions[xrExtIndex])) {
                            hasXrVkInstanceExtensions[xrExtIndex] = true;
                        }
                    }
                }
            }
            System.out.println("-----------");

            for (int index = 0; index < hasXrVkInstanceExtensions.length; index++) {
                if (!hasXrVkInstanceExtensions[index]) {
                    throw new RuntimeException("Missing required extension " + xrVkInstanceExtensions[index]);
                }
            }

            String[] instanceExtensions;
            if (hasExtDebugUtils) {
                System.out.println("Enabling vulkan debugger");
                instanceExtensions = Arrays.copyOf(xrVkInstanceExtensions, xrVkInstanceExtensions.length + 1);
                instanceExtensions[instanceExtensions.length - 1] = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
            } else {
                System.out.println("Can't start vulkan debugger because the following extension is missing: " + VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
                instanceExtensions = xrVkInstanceExtensions;
            }

            PointerBuffer ppExtensionNames = stack.callocPointer(instanceExtensions.length);
            for (int index = 0; index < instanceExtensions.length; index++) {
                ppExtensionNames.put(index, stack.UTF8(instanceExtensions[index]));
            }
            ciInstance.ppEnabledExtensionNames(ppExtensionNames);

            IntBuffer pPropertyCount = stack.callocInt(1);
            vkEnumerateInstanceLayerProperties(pPropertyCount, null);
            int propertyCount = pPropertyCount.get(0);

            VkLayerProperties.Buffer layerProps = VkLayerProperties.callocStack(propertyCount, stack);
            vkCheck(vkEnumerateInstanceLayerProperties(pPropertyCount, layerProps), "EnumerateInstanceLayerProperties");

            boolean hasValidationLayer = false;
            System.out.println("Available vulkan layers:");
            for (int index = 0; index < propertyCount; index++) {
                VkLayerProperties layerProp = layerProps.get(index);
                System.out.println(layerProp.layerNameString());
                if (layerProp.layerNameString().equals(VK_LAYER_LUNARG_STANDARD_VALIDATION)) {
                    hasValidationLayer = true;
                }
            }
            System.out.println("-------------");

            if (hasValidationLayer) {
                System.out.println("Enabling vulkan validation layer");
                PointerBuffer ppLayers = stack.callocPointer(1);
                ppLayers.put(0, stack.UTF8(VK_LAYER_LUNARG_STANDARD_VALIDATION));
                ciInstance.ppEnabledLayerNames(ppLayers);
            } else {
                System.out.println("Vulkan validation layer is not available");
            }

            PointerBuffer pInstance = stack.callocPointer(1);
            vkCheck(vkCreateInstance(ciInstance, null, pInstance), "CreateInstance");
            vkInstance = new VkInstance(pInstance.get(0), ciInstance);

            if (hasExtDebugUtils) {
                VkDebugUtilsMessengerCreateInfoEXT ciDebugUtils = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack);
                ciDebugUtils.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
                ciDebugUtils.messageSeverity(
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                );
                ciDebugUtils.messageType(
                    VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                );
                ciDebugUtils.pfnUserCallback((messageSeverity, messageTypes, pCallbackData, userData) -> {
                    VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    System.out.println("DebugUtils: " + callbackData.pMessageString());
                    return 0;
                });

                LongBuffer pDebug = stack.callocLong(1);
                vkCheck(vkCreateDebugUtilsMessengerEXT(vkInstance, ciDebugUtils, null, pDebug), "CreateDebugUtilsMessenger");
                vkDebugMessenger = pDebug.get(0);
            }

            IntBuffer pPhysicalDeviceCount = stack.callocInt(1);
            vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, null);
            int numPhysicalDevices = pPhysicalDeviceCount.get(0);

            PointerBuffer physicalDevices = stack.callocPointer(numPhysicalDevices);
            vkCheck(vkEnumeratePhysicalDevices(vkInstance, pPhysicalDeviceCount, physicalDevices), "EnumeratePhysicalDevices");
            System.out.println("Found " + numPhysicalDevices + " physical devices:");

            IntBuffer pCapXrDeviceExtensions = stack.callocInt(1);
            xrGetVulkanDeviceExtensionsKHR(xrInstance, xrSystemId, pCapXrDeviceExtensions, null);
            ByteBuffer pXrDeviceExtensions = stack.calloc(pCapXrDeviceExtensions.get(0));

            xrCheck(xrGetVulkanDeviceExtensionsKHR(xrInstance, xrSystemId, pCapXrDeviceExtensions, pXrDeviceExtensions), "GetVulkanDeviceExtensions");
            String[] xrDeviceExtensions = memUTF8(pXrDeviceExtensions).split(" ");
            if (xrDeviceExtensions.length > 0) {
                int lastIndex = xrDeviceExtensions.length - 1;
                String lastExtension = xrDeviceExtensions[lastIndex];
                int length = lastExtension.length();
                int lastChar = lastExtension.charAt(length - 1);

                // Filter out the last null terminator, if needed
                if (lastChar == 0) {
                    xrDeviceExtensions[lastIndex] = lastExtension.substring(0, length - 1);
                }
            }

            System.out.println(xrDeviceExtensions.length + " Vulkan device extensions are required for OpenXR:");
            for (String extension : xrDeviceExtensions) {
                System.out.println(extension);
            }
            System.out.println("-------------");

            PointerBuffer pPhysicalDeviceHandle = stack.callocPointer(1);
            // This is a bit of a hack to create a valid physical device handle to be used in the next function
            vkEnumeratePhysicalDevices(vkInstance, stack.ints(1), pPhysicalDeviceHandle);
            xrCheck(xrGetVulkanGraphicsDeviceKHR(xrInstance, xrSystemId, vkInstance, pPhysicalDeviceHandle), "GetVulkanGraphicsDevice");
            vkPhysicalDevice = new VkPhysicalDevice(pPhysicalDeviceHandle.get(0), vkInstance);

            VkPhysicalDeviceProperties pPhysicalProperties = VkPhysicalDeviceProperties.callocStack(stack);
            vkGetPhysicalDeviceProperties(vkPhysicalDevice, pPhysicalProperties);
            System.out.println("Chose " + pPhysicalProperties.deviceNameString());

            IntBuffer pQueueFamilyCount = stack.callocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, pQueueFamilyCount, null);
            int queueFamilyCount = pQueueFamilyCount.get(0);

            VkQueueFamilyProperties.Buffer pQueueFamilies = VkQueueFamilyProperties.callocStack(queueFamilyCount, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, pQueueFamilyCount, pQueueFamilies);
            System.out.println("Found " + queueFamilyCount + " queue families");

            int suitableQueueFamilyIndex = -1;
            for (int index = 0; index < queueFamilyCount; index++) {
                VkQueueFamilyProperties familyProps = pQueueFamilies.get(index);
                int flags = familyProps.queueFlags();
                if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    suitableQueueFamilyIndex = index;
                }
            }

            if (suitableQueueFamilyIndex == -1) {
                throw new RuntimeException("No queue family with graphics support was found");
            }

            vkQueueFamilyIndex = suitableQueueFamilyIndex;
            // We only use 1 queue
            vkQueueIndex = 0;

            VkDeviceQueueCreateInfo.Buffer cipDeviceQueue = VkDeviceQueueCreateInfo.callocStack(1, stack);
            VkDeviceQueueCreateInfo ciDeviceQueue = cipDeviceQueue.get(0);
            ciDeviceQueue.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
            ciDeviceQueue.queueFamilyIndex(suitableQueueFamilyIndex);
            ciDeviceQueue.pQueuePriorities(stack.floats(1.0f));

            VkDeviceCreateInfo ciDevice = VkDeviceCreateInfo.callocStack(stack);
            ciDevice.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            ciDevice.pQueueCreateInfos(cipDeviceQueue);
            PointerBuffer ppDeviceExtensions = stack.callocPointer(xrDeviceExtensions.length);
            for (int index = 0; index < xrDeviceExtensions.length; index++) {
                ppDeviceExtensions.put(index, stack.UTF8(xrDeviceExtensions[index]));
            }
            ciDevice.ppEnabledExtensionNames(ppDeviceExtensions);

            PointerBuffer pDevice = stack.callocPointer(1);
            vkCheck(vkCreateDevice(vkPhysicalDevice, ciDevice, null, pDevice), "CreateDevice");
            this.vkDevice = new VkDevice(pDevice.get(0), vkPhysicalDevice, ciDevice);

            PointerBuffer pQueue = stack.callocPointer(1);
            vkGetDeviceQueue(vkDevice, vkQueueFamilyIndex, vkQueueIndex, pQueue);
            this.vkQueue = new VkQueue(pQueue.get(0), vkDevice);
        }
    }

    private void destroyVk() {
        if (vkDebugMessenger != 0L) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, vkDebugMessenger, null);
        }
        if (vkDevice != null) {
            vkDestroyDevice(vkDevice, null);
        }
        if (vkInstance != null) {
            vkDestroyInstance(vkInstance, null);
        }
    }

    private void createXrVkSession() {
        try (MemoryStack stack = stackPush()) {

            XrGraphicsBindingVulkanKHR graphicsBinding = XrGraphicsBindingVulkanKHR.callocStack(stack);
            graphicsBinding.type(XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR);
            graphicsBinding.instance(vkInstance);
            graphicsBinding.physicalDevice(vkPhysicalDevice);
            graphicsBinding.device(vkDevice);
            graphicsBinding.queueFamilyIndex(vkQueueFamilyIndex);
            graphicsBinding.queueIndex(vkQueueIndex);

            XrSessionCreateInfo ciSession = XrSessionCreateInfo.callocStack(stack);
            ciSession.type(XR_TYPE_SESSION_CREATE_INFO);
            ciSession.systemId(xrSystemId);
            ciSession.next(graphicsBinding.address());

            PointerBuffer pSession = stack.callocPointer(1);
            xrCheck(xrCreateSession(xrInstance, ciSession, pSession), "CreateSession");
            xrVkSession = new XrSession(pSession.get(0), xrInstance);
            xrSessionState = XR_SESSION_STATE_IDLE;
            System.out.println("Session is " + xrVkSession);

            if (!missingXrDebug) {
                XrDebugUtilsMessengerCreateInfoEXT ciDebugUtils = XrDebugUtilsMessengerCreateInfoEXT.callocStack(stack);
                ciDebugUtils.type(XR_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
                ciDebugUtils.messageSeverities(
                    XR_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                    XR_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                    XR_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                );
                ciDebugUtils.messageTypes(
                    XR_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                    XR_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                    XR_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT |
                    XR_DEBUG_UTILS_MESSAGE_TYPE_CONFORMANCE_BIT_EXT
                );
                ciDebugUtils.userCallback((messageSeverity, messageTypes, pCallbackData, userData) -> {
                    XrDebugUtilsMessengerCallbackDataEXT callbackData = XrDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    System.out.println("XR Debug Utils: " + callbackData.messageString());
                    return 0;
                });

                System.out.println("Enabling OpenXR debug utils");
                PointerBuffer pMessenger = stack.callocPointer(1);
                xrCheck(xrCreateDebugUtilsMessengerEXT(xrInstance, ciDebugUtils, pMessenger), "CreateDebugUtilsMessenger");
                xrDebugMessenger = new XrDebugUtilsMessengerEXT(pMessenger.get(0), xrVkSession.getCapabilities());
            }
        }
    }

    private static XrPosef identityPose(MemoryStack stack) {
        XrQuaternionf poseOrientation = XrQuaternionf.callocStack(stack);
        poseOrientation.set(0, 0, 0, 1);

        XrVector3f posePosition = XrVector3f.callocStack(stack);
        posePosition.set(0, 0, 0);

        return XrPosef.callocStack(stack).set(poseOrientation, posePosition);
    }

    private void createRenderResources() {
        createSwapchains();
        createCommandPools();
        createRenderPass();
        createGraphicsPipelines();
        createRenderSpace();
    }

    private void createRenderSpace() {
        try (MemoryStack stack = stackPush()) {

            XrReferenceSpaceCreateInfo ciReferenceSpace = XrReferenceSpaceCreateInfo.callocStack(stack);
            ciReferenceSpace.type(XR_TYPE_REFERENCE_SPACE_CREATE_INFO);
            ciReferenceSpace.referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL);
            ciReferenceSpace.poseInReferenceSpace(identityPose(stack));

            PointerBuffer pRenderSpace = stack.callocPointer(1);
            xrCheck(xrCreateReferenceSpace(xrVkSession, ciReferenceSpace, pRenderSpace), "CreateReferenceSpace");
            renderSpace = new XrSpace(pRenderSpace.get(0), xrVkSession);
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {

            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.callocStack(2, stack);
            VkAttachmentDescription colorAttachment = attachments.get(0);
            colorAttachment.flags(0);
            colorAttachment.format(this.swapchainFormat);
            // TODO Take a closer look at this...
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentDescription depthAttachment = attachments.get(1);
            depthAttachment.flags(0);
            depthAttachment.format(VK_FORMAT_D32_SFLOAT);
            // TODO Not sure this even needs samples
            depthAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            depthAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            depthAttachment.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorAttachmentRefs = VkAttachmentReference.callocStack(1, stack);
            VkAttachmentReference colorAttachmentRef = colorAttachmentRefs.get(0);
            colorAttachmentRef.attachment(0);
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthAttachmentRef = VkAttachmentReference.callocStack(stack);
            depthAttachmentRef.attachment(1);
            depthAttachmentRef.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            // Ensure that the render pass doesn't begin too early
            VkSubpassDependency.Buffer dependencies = VkSubpassDependency.callocStack(1, stack);
            VkSubpassDependency dependency = dependencies.get(0);
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL);
            dependency.dstSubpass(0);
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.srcAccessMask(0);
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkSubpassDescription.Buffer subpasses = VkSubpassDescription.callocStack(1, stack);
            VkSubpassDescription subpass = subpasses.get(0);
            subpass.flags(0);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            // For some reason, I have to specify the colorAttachmentCount explicitly
            subpass.colorAttachmentCount(1);
            subpass.pColorAttachments(colorAttachmentRefs);
            subpass.pDepthStencilAttachment(depthAttachmentRef);

            VkRenderPassCreateInfo ciRenderPass = VkRenderPassCreateInfo.callocStack(stack);
            ciRenderPass.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            ciRenderPass.flags(0);
            ciRenderPass.pAttachments(attachments);
            ciRenderPass.pSubpasses(subpasses);
            ciRenderPass.pDependencies(dependencies);

            LongBuffer pRenderPass = stack.callocLong(1);
            vkCheck(vkCreateRenderPass(vkDevice, ciRenderPass, null, pRenderPass), "CreateRenderPass");
            this.vkRenderPass = pRenderPass.get(0);
        }
    }

    private void createGraphicsPipelines() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo ciPipelineLayout = VkPipelineLayoutCreateInfo.callocStack(stack);
            ciPipelineLayout.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            ciPipelineLayout.flags(0);
            // I will add uniform variables or push constants later
            ciPipelineLayout.pSetLayouts(null);
            ciPipelineLayout.pPushConstantRanges(null);

            LongBuffer pPipelineLayout = stack.callocLong(1);
            vkCheck(vkCreatePipelineLayout(vkDevice, ciPipelineLayout, null, pPipelineLayout), "CreatePipelineLayout");
            this.vkPipelineLayout = pPipelineLayout.get(0);
        }

        this.vkGraphicsPipelines = new long[this.swapchains.length];

        for (int swapchainIndex = 0; swapchainIndex < this.swapchains.length; swapchainIndex++) {
            if (
                swapchainIndex == 0 || this.swapchains[swapchainIndex - 1].width != this.swapchains[swapchainIndex].width
                || this.swapchains[swapchainIndex - 1].height != this.swapchains[swapchainIndex].height
            ) {
                createGraphicsPipeline(swapchainIndex);
            } else {
                // If the swapchain has the same size as the previous swapchain (expected case), share the graphics pipeline
                this.vkGraphicsPipelines[swapchainIndex] = this.vkGraphicsPipelines[swapchainIndex - 1];
            }
        }
    }

    private void createGraphicsPipeline(int swapchainIndex) {
        long vertexModule;
        long fragmentModule;

        try (MemoryStack stack = stackPush()) {

            VkShaderModuleCreateInfo ciVertexModule = VkShaderModuleCreateInfo.callocStack(stack);
            ciVertexModule.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            ByteBuffer vertexBytes = getVertexShaderBytes();
            ciVertexModule.pCode(vertexBytes);

            LongBuffer pShaderModule = stack.callocLong(1);
            vkCheck(vkCreateShaderModule(vkDevice, ciVertexModule, null, pShaderModule), "CreateShaderModule (vertex)");
            vertexModule = pShaderModule.get(0);

            VkShaderModuleCreateInfo ciFragmentModule = VkShaderModuleCreateInfo.callocStack(stack);
            ciFragmentModule.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            ByteBuffer fragmentBytes = getFragmentShaderBytes();
            ciFragmentModule.pCode(fragmentBytes);

            vkCheck(vkCreateShaderModule(vkDevice, ciFragmentModule, null, pShaderModule), "CreateShaderModule (fragment)");
            fragmentModule = pShaderModule.get(0);

            memFree(vertexBytes);
            memFree(fragmentBytes);
        }

        try (MemoryStack stack = stackPush()) {

            VkPipelineShaderStageCreateInfo.Buffer ciPipelineShaderStages = VkPipelineShaderStageCreateInfo.callocStack(2, stack);
            VkPipelineShaderStageCreateInfo ciVertexStage = ciPipelineShaderStages.get(0);
            ciVertexStage.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            ciVertexStage.flags(0);
            ciVertexStage.stage(VK_SHADER_STAGE_VERTEX_BIT);
            ciVertexStage.module(vertexModule);
            ciVertexStage.pName(stack.UTF8("main"));

            VkPipelineShaderStageCreateInfo ciFragmentStage = ciPipelineShaderStages.get(1);
            ciFragmentStage.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            ciFragmentStage.flags(0);
            ciFragmentStage.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            ciFragmentStage.module(fragmentModule);
            ciFragmentStage.pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer vertexBindingDescriptions = VkVertexInputBindingDescription.callocStack(1, stack);
            VkVertexInputBindingDescription vertexBindingDescription = vertexBindingDescriptions.get(0);
            vertexBindingDescription.binding(0);
            vertexBindingDescription.stride(VERTEX_SIZE);
            vertexBindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer vertexAttributeDescriptions = VkVertexInputAttributeDescription.callocStack(2, stack);
            VkVertexInputAttributeDescription attributePosition = vertexAttributeDescriptions.get(0);
            attributePosition.location(VERTEX_LOCATION_POSITION);
            attributePosition.binding(0);
            attributePosition.format(VK_FORMAT_R32G32B32_SFLOAT);
            attributePosition.offset(VERTEX_OFFSET_POSITION);

            VkVertexInputAttributeDescription attributeColor = vertexAttributeDescriptions.get(1);
            attributeColor.location(VERTEX_LOCATION_COLOR);
            attributeColor.binding(0);
            attributeColor.format(VK_FORMAT_R32G32B32_SFLOAT);
            attributeColor.offset(VERTEX_OFFSET_COLOR);

            VkPipelineVertexInputStateCreateInfo ciVertexInput = VkPipelineVertexInputStateCreateInfo.callocStack(stack);
            ciVertexInput.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            ciVertexInput.flags(0);
            ciVertexInput.pVertexBindingDescriptions(vertexBindingDescriptions);
            ciVertexInput.pVertexAttributeDescriptions(vertexAttributeDescriptions);

            VkPipelineInputAssemblyStateCreateInfo ciInputAssembly = VkPipelineInputAssemblyStateCreateInfo.callocStack(stack);
            ciInputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            ciInputAssembly.flags(0);
            ciInputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            ciInputAssembly.primitiveRestartEnable(false);

            VkPipelineRasterizationStateCreateInfo ciRasterizationState = VkPipelineRasterizationStateCreateInfo.callocStack(stack);
            ciRasterizationState.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            ciRasterizationState.flags(0);
            ciRasterizationState.depthClampEnable(false);
            ciRasterizationState.rasterizerDiscardEnable(false);
            ciRasterizationState.polygonMode(VK_POLYGON_MODE_FILL);
            // In real applications, you would normally use culling. But, it is not so great for debugging.
            // For instance, it could be the reason why you don't see anything if you messed up the winding order.
            ciRasterizationState.cullMode(VK_CULL_MODE_NONE);
            ciRasterizationState.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            ciRasterizationState.depthBiasEnable(false);

            VkViewport.Buffer viewports = VkViewport.callocStack(1, stack);
            VkViewport viewport = viewports.get(0);
            viewport.x(0f);
            viewport.y(0f);
            viewport.width(this.swapchains[swapchainIndex].width);
            viewport.height(this.swapchains[swapchainIndex].height);
            viewport.minDepth(0f);
            viewport.maxDepth(1f);

            VkRect2D.Buffer scissors = VkRect2D.callocStack(1, stack);
            VkRect2D scissor = scissors.get(0);
            // By using callocStack, the offset will have a default value of 0, which is desired
            scissor.offset(VkOffset2D.callocStack(stack));
            scissor.extent(VkExtent2D.callocStack(stack).set(
                this.swapchains[swapchainIndex].width,
                this.swapchains[swapchainIndex].height
            ));

            VkPipelineViewportStateCreateInfo ciViewport = VkPipelineViewportStateCreateInfo.callocStack(stack);
            ciViewport.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            ciViewport.flags(0);
            ciViewport.pViewports(viewports);
            ciViewport.pScissors(scissors);

            VkPipelineMultisampleStateCreateInfo ciMultisample = VkPipelineMultisampleStateCreateInfo.callocStack(stack);
            ciMultisample.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            ciMultisample.flags(0);
            // TODO Look into this
            ciMultisample.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            ciMultisample.sampleShadingEnable(false);
            // I won't ignore a part of the samples
            ciMultisample.pSampleMask(null);
            // I won't use transparency in this example
            ciMultisample.alphaToCoverageEnable(false);
            ciMultisample.alphaToOneEnable(false);

            VkPipelineDepthStencilStateCreateInfo ciDepthStencil = VkPipelineDepthStencilStateCreateInfo.callocStack(stack);
            ciDepthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            ciDepthStencil.flags(0);
            ciDepthStencil.depthTestEnable(true);
            ciDepthStencil.depthWriteEnable(true);
            ciDepthStencil.depthCompareOp(VK_COMPARE_OP_LESS);
            ciDepthStencil.depthBoundsTestEnable(false);
            ciDepthStencil.stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer atsColorBlend = VkPipelineColorBlendAttachmentState.callocStack(1, stack);
            // We only have 1 color attachment, so we only use 1 color blend attachment state
            VkPipelineColorBlendAttachmentState atColorBlend = atsColorBlend.get(0);
            // We won't be doing any fancy blending
            atColorBlend.blendEnable(false);
            atColorBlend.colorWriteMask(
                VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT
            );

            VkPipelineColorBlendStateCreateInfo ciColorBlend = VkPipelineColorBlendStateCreateInfo.callocStack(stack);
            ciColorBlend.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            ciColorBlend.flags(0);
            ciColorBlend.logicOpEnable(false);
            ciColorBlend.pAttachments(atsColorBlend);

            VkGraphicsPipelineCreateInfo.Buffer ciGraphicsPipelines = VkGraphicsPipelineCreateInfo.callocStack(1, stack);

            // In this example, I will use only 1 graphics pipeline
            VkGraphicsPipelineCreateInfo ciPipeline = ciGraphicsPipelines.get(0);
            ciPipeline.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            ciPipeline.flags(0);
            ciPipeline.renderPass(this.vkRenderPass);
            ciPipeline.layout(this.vkPipelineLayout);
            ciPipeline.pStages(ciPipelineShaderStages);
            ciPipeline.pVertexInputState(ciVertexInput);
            ciPipeline.pInputAssemblyState(ciInputAssembly);
            // This example won't use a tessellation shader
            ciPipeline.pTessellationState(null);
            ciPipeline.pViewportState(ciViewport);
            ciPipeline.pRasterizationState(ciRasterizationState);
            ciPipeline.pMultisampleState(ciMultisample);
            ciPipeline.pDepthStencilState(ciDepthStencil);
            ciPipeline.pColorBlendState(ciColorBlend);
            // TODO The rest of the values

            LongBuffer pPipelines = stack.callocLong(1);
            vkCheck(vkCreateGraphicsPipelines(vkDevice, VK_NULL_HANDLE, ciGraphicsPipelines, null, pPipelines), "CreateGraphicsPipelines");
            this.vkGraphicsPipelines[swapchainIndex] = pPipelines.get(0);
        }
    }

    private void createCommandPools() {
        try (MemoryStack stack = stackPush()) {

            VkCommandPoolCreateInfo ciCommandPool = VkCommandPoolCreateInfo.callocStack(stack);
            ciCommandPool.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            // In this tutorial, we will create and destroy the command buffers every frame
            ciCommandPool.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            ciCommandPool.queueFamilyIndex(vkQueueFamilyIndex);

            LongBuffer pCommandPool = stack.callocLong(1);
            vkCheck(vkCreateCommandPool(vkDevice, ciCommandPool, null, pCommandPool), "CreateCommandPool");

            this.vkCommandPool = pCommandPool.get(0);
        }
    }

    private void createSwapchains() {
        try (MemoryStack stack = stackPush()) {

            IntBuffer pNumFormats = stack.callocInt(1);
            xrCheck(xrEnumerateSwapchainFormats(xrVkSession, pNumFormats, null), "EnumerateSwapchainFormats");

            int numFormats = pNumFormats.get(0);
            LongBuffer formats = stack.callocLong(numFormats);
            xrCheck(xrEnumerateSwapchainFormats(xrVkSession, pNumFormats, formats), "EnumerateSwapchainFormats");

            // SRGB formats are preferred due to the human perception of colors
            // Non-alpha formats are preferred because I don't intend to use it and it would spare memory
            int[] preferredFormats = {
                VK_FORMAT_R8G8B8_SRGB, VK_FORMAT_B8G8R8_SRGB,
                VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB,
                VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_B8G8R8_UNORM,
                VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM
            };
            boolean[] hasPreferredFormats = new boolean[preferredFormats.length];

            System.out.println("There are " + numFormats + " swapchain formats:");
            for (int index = 0; index < numFormats; index++) {
                long format = formats.get(index);
                String formatName = findConstantMeaning(VK10.class, name -> name.startsWith("VK_FORMAT_"), format);
                if (formatName == null) {
                    formatName = "unknown";
                }
                System.out.println(formatName + " (" + format + ")");

                for (int prefIndex = 0; prefIndex < preferredFormats.length; prefIndex++) {
                    if (format == preferredFormats[prefIndex]) {
                        hasPreferredFormats[prefIndex] = true;
                    }
                }
            }
            System.out.println("--------------");

            swapchainFormat = -1;

            // Pick the best format available
            for (int prefIndex = 0; prefIndex < preferredFormats.length; prefIndex++) {
                if (hasPreferredFormats[prefIndex]) {
                    swapchainFormat = preferredFormats[prefIndex];
                    break;
                }
            }

            if (swapchainFormat == -1) {
                // Damn... what kind of graphics card/xr runtime is this?
                // Well... if we can't find any format we like, we will just pick the first one
                swapchainFormat = (int) formats.get(0);
            }

            System.out.println("Chose swapchain format " + swapchainFormat);

            IntBuffer pNumViewConfigurations = stack.callocInt(1);
            xrEnumerateViewConfigurations(xrInstance, xrSystemId, pNumViewConfigurations, null);
            int numViewConfigurations = pNumViewConfigurations.get(0);
            System.out.println("There are " + numViewConfigurations + " view configurations:");
            IntBuffer viewConfigurations = stack.callocInt(numViewConfigurations);
            xrCheck(xrEnumerateViewConfigurations(xrInstance, xrSystemId, pNumViewConfigurations, viewConfigurations), "EnumerateViewConfigurations");

            boolean hasPrimarySterio = false;
            for (int viewIndex = 0; viewIndex < numViewConfigurations; viewIndex++) {
                int viewConfiguration = viewConfigurations.get(viewIndex);
                System.out.println(findConstantMeaning(XR10.class, constantName -> constantName.startsWith("XR_VIEW_CONFIGURATION_TYPE_"), viewConfiguration) + " (" + viewConfiguration + ")");
                if (viewConfiguration == XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
                    hasPrimarySterio = true;
                }
            }
            System.out.println("~~~~~~~~~~");

            // I prefer primary stereo. If that is not available, I will go for the first best alternative.
            if (hasPrimarySterio) {
                viewConfiguration = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            } else {
                viewConfiguration = viewConfigurations.get(0);
            }
            System.out.println("Chose " + viewConfiguration);

            IntBuffer pNumViews = stack.callocInt(1);
            xrEnumerateViewConfigurationViews(xrInstance, xrSystemId, viewConfiguration, pNumViews, null);
            int numViews = pNumViews.get(0);
            System.out.println("There are " + numViews + " views");
            XrViewConfigurationView.Buffer viewConfigurationViews = XrViewConfigurationView.callocStack(numViews, stack);
            for (int viewIndex = 0; viewIndex < numViews; viewIndex++) {
                viewConfigurationViews.get(viewIndex).type(XR_TYPE_VIEW_CONFIGURATION_VIEW);
            }
            xrCheck(xrEnumerateViewConfigurationViews(xrInstance, xrSystemId, viewConfiguration, pNumViews, viewConfigurationViews), "EnumerateViewConfigurationViews");

            this.swapchains = new SwapchainWrapper[numViews];
            for (int swapchainIndex = 0; swapchainIndex < numViews; swapchainIndex++) {
                XrViewConfigurationView viewConfig = viewConfigurationViews.get(swapchainIndex);

                XrSwapchainCreateInfo ciSwapchain = XrSwapchainCreateInfo.callocStack(stack);
                ciSwapchain.type(XR_TYPE_SWAPCHAIN_CREATE_INFO);
                ciSwapchain.createFlags(0);
                ciSwapchain.usageFlags(
                    XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                    XR_SWAPCHAIN_USAGE_SAMPLED_BIT
                );
                ciSwapchain.format(swapchainFormat);
                ciSwapchain.width(viewConfig.recommendedImageRectWidth());
                ciSwapchain.height(viewConfig.recommendedImageRectHeight());
                ciSwapchain.sampleCount(viewConfig.recommendedSwapchainSampleCount());
                System.out.println("Creating a swapchain of " +
                                   ciSwapchain.width() + " x " +
                                   ciSwapchain.height() + " with " +
                                   ciSwapchain.sampleCount() + " samples");
                ciSwapchain.mipCount(1);
                ciSwapchain.arraySize(1);
                ciSwapchain.faceCount(1);

                PointerBuffer pSwapchain = stack.callocPointer(1);
                xrCheck(xrCreateSwapchain(xrVkSession, ciSwapchain, pSwapchain), "CreateSwapchain");
                XrSwapchain swapchain = new XrSwapchain(pSwapchain.get(0), xrVkSession);


                IntBuffer pNumImages = stack.callocInt(1);
                xrEnumerateSwapchainImages(swapchain, pNumImages, null);

                int numImages = pNumImages.get(0);
                System.out.println("Swapchain " + swapchainIndex + " has " + numImages + " images");
                XrSwapchainImageVulkanKHR.Buffer images = XrSwapchainImageVulkanKHR.callocStack(numImages, stack);
                for (int imageIndex = 0; imageIndex < numImages; imageIndex++) {
                    images.get(imageIndex).type(XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR);
                }
                xrCheck(xrEnumerateSwapchainImages(
                    swapchain, pNumImages,
                    XrSwapchainImageBaseHeader.create(images.address(), images.capacity())
                ), "EnumerateSwapchainImages");

                long[] imagesArray = new long[numImages];
                for (int imageIndex = 0; imageIndex < numImages; imageIndex++) {
                    imagesArray[imageIndex] = images.get(imageIndex).image();
                }

                this.swapchains[swapchainIndex] = new SwapchainWrapper(
                    new XrSwapchain(pSwapchain.get(0), xrVkSession),
                    ciSwapchain.width(), ciSwapchain.height(), ciSwapchain.sampleCount(),
                    imagesArray
                );
            }
        }
    }

    private XrCompositionLayerProjectionView.Buffer createProjectionViews(MemoryStack stack, long displayTime) {

        XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.callocStack(stack);
        viewLocateInfo.type(XR_TYPE_VIEW_LOCATE_INFO);
        viewLocateInfo.viewConfigurationType(viewConfiguration);
        viewLocateInfo.displayTime(displayTime);
        viewLocateInfo.space(renderSpace);

        XrViewState viewState = XrViewState.callocStack(stack);
        viewState.type(XR_TYPE_VIEW_STATE);

        IntBuffer pNumViews = stack.ints(swapchains.length);
        XrView.Buffer views = XrView.callocStack(swapchains.length, stack);
        for (int viewIndex = 0; viewIndex < swapchains.length; viewIndex++) {
            views.get(viewIndex).type(XR_TYPE_VIEW);
        }

        int locateViewResult = xrLocateViews(xrVkSession, viewLocateInfo, viewState, pNumViews, views);
        if (locateViewResult != XR_SESSION_LOSS_PENDING) {
            xrCheck(locateViewResult, "LocateViews");
        }

        long viewFlags = viewState.viewStateFlags();
        if ((viewFlags & XR_VIEW_STATE_POSITION_VALID_BIT) == 0) {
            System.out.println("View position invalid; abort rendering this frame");
            return null;
        }
        if ((viewFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
            System.out.println("View orientation invalid; abort rendering this frame");
            return null;
        }

        XrCompositionLayerProjectionView.Buffer projectionViews = XrCompositionLayerProjectionView.callocStack(swapchains.length, stack);
        for (int viewIndex = 0; viewIndex < swapchains.length; viewIndex++) {

            SwapchainWrapper swapchain = swapchains[viewIndex];
            XrRect2Di subImageRect = XrRect2Di.callocStack(stack);
            // By using calloc, it will automatically get the desired value of 0
            subImageRect.offset(XrOffset2Di.callocStack(stack));
            subImageRect.extent(XrExtent2Di.callocStack(stack).set(swapchain.width, swapchain.height));

            XrSwapchainSubImage subImage = XrSwapchainSubImage.callocStack(stack);
            subImage.swapchain(swapchain.swapchain);
            subImage.imageRect(subImageRect);
            subImage.imageArrayIndex(0);

            XrView currentView = views.get(viewIndex);

            XrCompositionLayerProjectionView projectionView = projectionViews.get(viewIndex);
            projectionView.type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW);
            projectionView.pose(currentView.pose());
            projectionView.fov(currentView.fov());
            projectionView.subImage(subImage);
        }

        return projectionViews;
    }

    private void updateSessionState(MemoryStack stack) {
        // Use malloc instead of calloc because this will be called frequently
        XrEventDataBuffer pollEventData = XrEventDataBuffer.mallocStack(stack);

        while (true) {
            pollEventData.type(XR_TYPE_EVENT_DATA_BUFFER);
            pollEventData.next(NULL);

            int pollResult = xrPollEvent(xrInstance, pollEventData);
            if (pollResult != XR_EVENT_UNAVAILABLE) {
                xrCheck(pollResult, "PollEvent");

                if (pollEventData.type() == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    XrEventDataSessionStateChanged sessionEvent = XrEventDataSessionStateChanged.create(pollEventData.address());
                    xrSessionState = sessionEvent.state();
                    System.out.println("Changed session state to " + xrSessionState);
                } else {
                    System.out.println("Received another event");
                }
            } else {
                break;
            }
        }
    }

    private void loopXrSession() {

        // This is a safety check for debugging. Set to 0 to disable this.
        long endTime = System.currentTimeMillis() + 500;

        boolean startedSession = false;

        while (true) {
            System.out.println("Iteration: session state is " + xrSessionState);
            try (MemoryStack stack = stackPush()) {
                updateSessionState(stack);

                if (xrSessionState == XR_SESSION_STATE_IDLE) {
                    try {
                        System.out.println("Session is idle");
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Shouldn't happen", e);
                    }
                    continue;
                }

                boolean timeoutStop = endTime != 0 && System.currentTimeMillis() > endTime;
                if (timeoutStop && startedSession) {
                    int exitResult = xrRequestExitSession(xrVkSession);
                    if (exitResult != XR_SESSION_LOSS_PENDING) {
                        xrCheck(exitResult, "RequestExitSession");
                    }
                    startedSession = false;
                }

                if (xrSessionState == XR_SESSION_STATE_STOPPING) {
                    xrEndSession(xrVkSession);
                }
                if (
                    xrSessionState == XR_SESSION_STATE_LOSS_PENDING || xrSessionState == XR_SESSION_STATE_EXITING ||
                    xrSessionState == XR_SESSION_STATE_STOPPING
                ) {
                    break;
                }

                if (timeoutStop) {
                    continue;
                }

                if (xrSessionState == XR_SESSION_STATE_READY && !startedSession) {
                    XrSessionBeginInfo biSession = XrSessionBeginInfo.callocStack(stack);
                    biSession.type(XR_TYPE_SESSION_BEGIN_INFO);
                    biSession.primaryViewConfigurationType(viewConfiguration);

                    System.out.println("Begin session");
                    xrCheck(xrBeginSession(xrVkSession, biSession), "BeginSession");
                    startedSession = true;
                }

                updateSessionState(stack);

                if (
                    xrSessionState == XR_SESSION_STATE_SYNCHRONIZED || xrSessionState == XR_SESSION_STATE_VISIBLE ||
                    xrSessionState == XR_SESSION_STATE_FOCUSED || xrSessionState == XR_SESSION_STATE_READY
                ) {
                    XrFrameState frameState = XrFrameState.callocStack(stack);
                    frameState.type(XR_TYPE_FRAME_STATE);
                    int waitResult = xrWaitFrame(xrVkSession, null, frameState);

                    // SESSION_LOSS_PENDING is also a valid result, but we will ignore it
                    if (waitResult != XR_SESSION_LOSS_PENDING) {
                        xrCheck(waitResult, "WaitFrame");
                    }

                    int beginResult = xrBeginFrame(xrVkSession, null);
                    if (beginResult != XR_SESSION_LOSS_PENDING) {
                        xrCheck(beginResult, "BeginFrame");
                    }

                    PointerBuffer layers = null;
                    if (frameState.shouldRender()) {


                        XrCompositionLayerProjection layer = XrCompositionLayerProjection.callocStack(stack);
                        layer.type(XR_TYPE_COMPOSITION_LAYER_PROJECTION);
                        // If we were to use alpha, we should set the alpha layer flag
                        layer.layerFlags(0);
                        layer.space(renderSpace);

                        XrCompositionLayerProjectionView.Buffer projectionViews = createProjectionViews(stack, frameState.predictedDisplayTime());
                        if (projectionViews != null) {
                            layer.views(projectionViews);
                            layers = stack.pointers(layer.address());
                        }

                        for (int swapchainIndex = 0; swapchainIndex < swapchains.length; swapchainIndex++) {
                            XrSwapchain swapchain = this.swapchains[swapchainIndex].swapchain;
                            IntBuffer pImageIndex = stack.callocInt(1);
                            int acquireResult = xrAcquireSwapchainImage(swapchain, null, pImageIndex);
                            if (acquireResult != XR_SESSION_LOSS_PENDING) {
                                xrCheck(acquireResult, "AcquireSwapchainImage");
                            }

                            int imageIndex = pImageIndex.get(0);
                            long swapchainImage = swapchains[swapchainIndex].images[imageIndex];

                            XrSwapchainImageWaitInfo wiSwapchainImage = XrSwapchainImageWaitInfo.callocStack(stack);
                            wiSwapchainImage.type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO);
                            // Time out after 1 second. If we would have to wait so long, something is seriously wrong
                            wiSwapchainImage.timeout(1_000_000_000);

                            int waitImageResult = xrWaitSwapchainImage(swapchain, wiSwapchainImage);
                            if (waitImageResult != XR_SESSION_LOSS_PENDING) {
                                xrCheck(waitImageResult, "WaitSwapchainImage");
                            }

                            // For now, we will simply create and destroy command buffers every frame
                            VkCommandBufferAllocateInfo aiCommandBuffer = VkCommandBufferAllocateInfo.callocStack(stack);
                            aiCommandBuffer.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                            aiCommandBuffer.commandPool(vkCommandPool);
                            aiCommandBuffer.commandBufferCount(1);
                            aiCommandBuffer.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);

                            PointerBuffer pCommandBuffer = stack.callocPointer(1);
                            vkCheck(vkAllocateCommandBuffers(vkDevice, aiCommandBuffer, pCommandBuffer), "AllocateCommandBuffers");

                            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), vkDevice);

                            VkCommandBufferBeginInfo biCommandBuffer = VkCommandBufferBeginInfo.callocStack(stack);
                            biCommandBuffer.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                            biCommandBuffer.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

                            vkCheck(vkBeginCommandBuffer(commandBuffer, biCommandBuffer), "BeginCommandBuffer");
                            // TODO Record render commands
                            vkCheck(vkEndCommandBuffer(commandBuffer), "EndCommandBuffer");

                            VkSubmitInfo submitInfo = VkSubmitInfo.callocStack(stack);
                            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
                            submitInfo.pCommandBuffers(stack.pointers(commandBuffer.address()));

                            VkFenceCreateInfo ciFence = VkFenceCreateInfo.callocStack(stack);
                            ciFence.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);

                            // Not the most efficient way of doing things, but should be sufficient for an example
                            LongBuffer pFence = stack.callocLong(1);
                            vkCheck(vkCreateFence(vkDevice, ciFence, null, pFence), "CreateFence");
                            long fence = pFence.get(0);

                            vkCheck(vkQueueSubmit(vkQueue, submitInfo, fence), "QueueSubmit");
                            vkCheck(vkWaitForFences(vkDevice, pFence, true, 1_000_000_000), "WaitForFences");
                            vkFreeCommandBuffers(vkDevice, this.vkCommandPool, commandBuffer);
                            vkDestroyFence(vkDevice, fence, null);

                            int releaseResult = xrReleaseSwapchainImage(swapchain, null);
                            if (releaseResult != XR_SESSION_LOSS_PENDING) {
                                xrCheck(releaseResult, "ReleaseSwapchainImage");
                            }
                        }
                    } else {
                        System.out.println("Skip frame");
                    }

                    XrFrameEndInfo eiFrame = XrFrameEndInfo.callocStack(stack);
                    eiFrame.type(XR_TYPE_FRAME_END_INFO);
                    eiFrame.displayTime(frameState.predictedDisplayTime());
                    eiFrame.environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
                    eiFrame.layers(layers);

                    int endResult = xrEndFrame(xrVkSession, eiFrame);
                    if (endResult != XR_SESSION_LOSS_PENDING) {
                        xrCheck(endResult, "EndFrame");
                    }
                }
            }
        }
    }

    private void destroyRenderResources() {

        // TODO Ensure that all rendering is finished

        if (swapchains != null) {
            for (SwapchainWrapper swapchain : swapchains) {
                if (swapchain != null) {
                    xrDestroySwapchain(swapchain.swapchain);
                }
            }
        }

        if (vkCommandPool != 0) {
            vkDestroyCommandPool(vkDevice, vkCommandPool, null);
        }

        if (renderSpace != null) {
            xrDestroySpace(renderSpace);
        }

        if (this.vkRenderPass != 0) {
            vkDestroyRenderPass(vkDevice, vkRenderPass, null);
        }

        if (vkPipelineLayout != 0) {
            vkDestroyPipelineLayout(vkDevice, vkPipelineLayout, null);
        }

        if (vkGraphicsPipelines != null) {
            for (long graphicsPipeline : vkGraphicsPipelines) {
                if (graphicsPipeline != 0) {
                    vkDestroyPipeline(vkDevice, graphicsPipeline, null);
                }
            }
        }
    }

    private void destroyXrVkSession() {
        if (xrVkSession != null) {
            xrDestroySession(xrVkSession);
        }
    }

    private void destroyXrInstance() {
        if (xrDebugMessenger != null) {
            xrDestroyDebugUtilsMessengerEXT(xrDebugMessenger);
        }
        if (xrInstance != null) {
            xrDestroyInstance(xrInstance);
        }
    }

    private static void xrCheck(int result, String functionName) {
        if (result != XR_SUCCESS) {
            String constantName = findConstantMeaning(XR10.class, candidateConstant -> candidateConstant.startsWith("XR_ERROR_"), result);
            throw new RuntimeException("OpenXR function " + functionName + " returned " + result + " (" + constantName + ")");
        }
    }

    private static void vkCheck(int result, String functionName) {
        if (result != VK_SUCCESS) {
            String constantName = findConstantMeaning(VK10.class, candidateConstant -> candidateConstant.startsWith("VK_ERROR_"), result);
            throw new RuntimeException("Vulkan function " + functionName + " returned " + result + " (" + constantName + ")");
        }
    }

    private static String findConstantMeaning(Class<?> containingClass, Predicate<String> constantFilter, Object constant) {
        Field[] fields = containingClass.getFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && constantFilter.test(field.getName())) {
                try {
                    Object value = field.get(null);
                    if (value instanceof Number && constant instanceof Number) {
                        if (((Number) value).longValue() == ((Number) constant).longValue()) {
                            return field.getName();
                        }
                    }
                    if (constant.equals(value)) {
                        return field.getName();
                    }
                } catch (IllegalAccessException ex) {
                    // Ignore private fields
                }
            }
        }

        return null;
    }
}
