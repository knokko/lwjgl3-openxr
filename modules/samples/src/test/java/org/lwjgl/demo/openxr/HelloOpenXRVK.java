/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.openxr;

import org.lwjgl.*;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.nio.*;
import java.util.*;

import static org.lwjgl.demo.openxr.HelloOpenXR.*;
import static org.lwjgl.openxr.EXTDebugUtils.*;
import static org.lwjgl.openxr.KHRVulkanEnable.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;

public class HelloOpenXRVK {

    private static final String VK_LAYER_LUNARG_STANDARD_VALIDATION = "VK_LAYER_LUNARG_standard_validation";

    public static void main(String[] args) {
        XR.create();

        HelloOpenXRVK classInstance = new HelloOpenXRVK();
        classInstance.start();
    }

    private XrInstance xrInstance;
    private XrDebugUtilsMessengerEXT xrDebugMessenger;
    private long xrSystemId;

    private XrSession xrVkSession;

    private VkInstance vkInstance;
    private long vkDebugMessenger;
    private VkPhysicalDevice vkPhysicalDevice;
    private VkDevice vkDevice;
    private int vkQueueFamilyIndex;
    private int vkQueueIndex;

    private void start() {
        try {
            createXrInstance();
            initXrSystem();
            initVk();
            createXrVkSession();
        } catch (RuntimeException ex) {
            System.err.println("OpenXR testing failed:");
            ex.printStackTrace();
        }

        // Always clean up
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
            boolean missingDebug = true;
            while (properties.hasRemaining()) {
                XrExtensionProperties prop          = properties.get();
                String                extensionName = prop.extensionNameString();
                System.out.println(extensionName);
                extensions.put(memASCII(extensionName));
                if (extensionName.equals(KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME)) {
                    missingVulkan = false;
                }
                if (extensionName.equals("XR_EXT_debug_utils")) {
                    missingDebug = false;
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
            if (missingDebug) {
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

            if (!missingDebug) {
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
                xrDebugMessenger = null; // TODO Find a way to create a proper instance of this
            }
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
            long minVkMinor = XR10.VK_VERSION_MINOR(minApiVersion); // TODO Typo?
            long minVkPatch = XR10.VK_VERSION_PATCH(minApiVersion); // Also typo?
            System.out.println("Minimum Vulkan API version: " + minVkMajor + "." + minVkMinor + "." + minVkPatch);
            System.out.println("Maximum Vulkan API version: " + XR_VERSION_MAJOR(maxApiVersion) + "." + XR10.VK_VERSION_MINOR(maxApiVersion) + "." + XR10.VK_VERSION_PATCH(maxApiVersion));

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
            vkDevice = new VkDevice(pDevice.get(0), vkPhysicalDevice, ciDevice);
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
            System.out.println("Session is " + xrVkSession);
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
            throw new RuntimeException("OpenXR function " + functionName + " returned " + result);
        }
    }

    private static void vkCheck(int result, String functionName) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Vulkan function " + functionName + " returned " + result);
        }
    }
}
