/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.openxr;

import org.joml.*;
import org.lwjgl.*;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.Random;
import java.util.function.*;

import static org.joml.Math.*;
import static org.lwjgl.openxr.EXTDebugUtils.*;
import static org.lwjgl.openxr.KHRVulkanEnable.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;

public class HelloOpenXRVK {

    private static final String VK_LAYER_LUNARG_STANDARD_VALIDATION = "VK_LAYER_LUNARG_standard_validation";

    private static final int VERTEX_SIZE = 2 * 3 * 4;
    private static final int VERTEX_LOCATION_POSITION = 0;
    private static final int VERTEX_LOCATION_COLOR = 1;
    private static final int VERTEX_OFFSET_POSITION = 0;
    private static final int VERTEX_OFFSET_COLOR = 1 * 3 * 4;

    private static final int INDEX_SIZE = 2;
    private static final int INDEX_TYPE = VK_INDEX_TYPE_UINT16;

    private static class BigModel {

        static final int NUM_CUBES = 500;

        static final int NUM_VERTICES = NUM_CUBES * 6 * 4;
        static final int NUM_INDICES = NUM_CUBES * 6 * 6;
    }

    private static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    private static class SwapchainImage {
        final long colorImage;
        final long colorImageView;
        final long framebuffer;

        SwapchainImage(long colorImage, long colorImageView, long framebuffer) {
            this.colorImage = colorImage;
            this.colorImageView = colorImageView;
            this.framebuffer = framebuffer;
        }
    }

    private static class SwapchainWrapper {

        final XrSwapchain swapchain;
        final int width, height, numSamples;
        final long depthImage;
        final long depthImageView;
        final long depthImageMemory;
        final SwapchainImage[] images;

        SwapchainWrapper(
            XrSwapchain swapchain, int width, int height, int numSamples,
            long depthImage, long depthImageView, long depthImageMemory, SwapchainImage[] images
        ) {
            if (swapchain == null) throw new NullPointerException("swapchain");
            this.swapchain = swapchain;
            if (width <= 0) throw new IllegalArgumentException("width is " + width);
            this.width = width;
            if (height <= 0) throw new IllegalArgumentException("height is " + height);
            this.height = height;

            if (numSamples <= 0) throw new IllegalArgumentException("numSamples is " + numSamples);

            if (images == null) throw new IllegalArgumentException("images are null");
            this.numSamples = numSamples;
            for (SwapchainImage image : images) {
                if (image == null) {
                    throw new IllegalArgumentException("An image is null");
                }

                if (image.colorImage == 0) throw new IllegalArgumentException("A color image is 0");
                if (image.colorImageView == 0) throw new IllegalArgumentException("A color image view is 0");
                if (image.framebuffer == 0) throw new IllegalArgumentException("A framebuffer is 0");
            }
            this.images = images;

            if (depthImage == 0) throw new IllegalArgumentException("The depth image is 0");
            this.depthImage = depthImage;
            if (depthImageView == 0) throw new IllegalArgumentException("The depth image view is 0");
            this.depthImageView = depthImageView;
            if (depthImageMemory == 0) throw new IllegalArgumentException("The depth image memory is 0");
            this.depthImageMemory = depthImageMemory;
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
    private XrActionSet xrActionSet;
    private XrAction xrHandAction;
    private XrSpace xrLeftHandSpace;
    private XrSpace xrRightHandSpace;
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

    private long vkDeviceMemory;
    private long vkBigBuffer;

    private SwapchainWrapper[] swapchains;
    private int viewConfiguration;
    private int swapchainFormat;

    private XrSpace renderSpace;

    // The default value can be used in edge cases where the position tracking of the driver fails right at the start
    // Let's hope it won't be used a lot
    private Matrix4f lastCameraMatrix = new Matrix4f().perspective(toRadians(70f), 0.7f, 0.01f, 100f, true);

    private void start() {
        try {
            createXrInstance();
            initXrSystem();
            initVk();
            createXrVkSession();
            initXrActions();
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
            XrApiLayerProperties.Buffer pLayers = XRHelper.prepareApiLayerProperties(stack, numLayers);
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

            XrExtensionProperties.Buffer properties = XRHelper.prepareExtensionProperties(stack, numExtensions);
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
            System.out.println("Create instance...");
            xrCheck(XR10.xrCreateInstance(createInfo, instancePtr), "CreateInstance");
            xrInstance = new XrInstance(instancePtr.get(0), createInfo);
            System.out.println("Created instance");
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

    private void initXrActions() {
        try (MemoryStack stack = stackPush()) {

            XrActionSetCreateInfo ciActionSet = XrActionSetCreateInfo.callocStack(stack);
            ciActionSet.type(XR_TYPE_ACTION_SET_CREATE_INFO);
            ciActionSet.priority(1);
            ciActionSet.actionSetName(stack.UTF8("handcontrols"));
            ciActionSet.localizedActionSetName(stack.UTF8("Demo Hand Controls"));

            PointerBuffer pActionSet = stack.callocPointer(1);
            xrCheck(xrCreateActionSet(xrInstance, ciActionSet, pActionSet), "CreateActionSet");
            this.xrActionSet = new XrActionSet(pActionSet.get(0), xrVkSession);

            LongBuffer pLeftHandPath = stack.callocLong(1);
            LongBuffer pRightHandPath = stack.callocLong(1);
            xrCheck(xrStringToPath(xrInstance, stack.UTF8("/user/hand/left"), pLeftHandPath), "StringToPath");
            xrCheck(xrStringToPath(xrInstance, stack.UTF8("/user/hand/right"), pRightHandPath), "StringToPath");
            long leftHandPath = pLeftHandPath.get(0);
            long rightHandPath = pRightHandPath.get(0);

            XrActionCreateInfo ciActionHands = XrActionCreateInfo.callocStack(stack);
            ciActionHands.type(XR_TYPE_ACTION_CREATE_INFO);
            ciActionHands.actionType(XR_ACTION_TYPE_POSE_INPUT);
            ciActionHands.actionName(stack.UTF8("handpose"));
            ciActionHands.localizedActionName(stack.UTF8("Hand pose"));
            ciActionHands.subactionPaths(stack.longs(leftHandPath, rightHandPath));

            PointerBuffer pActionHands = stack.callocPointer(1);
            xrCheck(xrCreateAction(xrActionSet, ciActionHands, pActionHands), "CreateAction");
            this.xrHandAction = new XrAction(pActionHands.get(0), xrVkSession);

            // TODO Also create a handTrigger action

            LongBuffer pInteractionProfile = stack.callocLong(1);
            xrCheck(xrStringToPath(xrInstance, stack.UTF8("/interaction_profiles/khr/simple_controller"), pInteractionProfile), "StringToPath");
            long interactionProfile = pInteractionProfile.get(0);

            LongBuffer pPathLeftHandPose = stack.callocLong(1);
            LongBuffer pPathRightHandPose = stack.callocLong(1);
            xrCheck(xrStringToPath(xrInstance, stack.UTF8("/user/hand/left/input/grip/pose"), pPathLeftHandPose), "StringToPath");
            xrCheck(xrStringToPath(xrInstance, stack.UTF8("/user/hand/right/input/grip/pose"), pPathRightHandPose), "StringToPath");
            long pathLeftHandPose = pPathLeftHandPose.get(0);
            long pathRightHandPose = pPathRightHandPose.get(0);

            XrActionSuggestedBinding.Buffer suggestedBindings = XrActionSuggestedBinding.callocStack(2, stack);
            suggestedBindings.get(0).action(xrHandAction);
            suggestedBindings.get(0).binding(pathLeftHandPose);
            suggestedBindings.get(1).action(xrHandAction);
            suggestedBindings.get(1).binding(pathRightHandPose);

            XrInteractionProfileSuggestedBinding profileSuggestedBinding = XrInteractionProfileSuggestedBinding.callocStack(stack);
            profileSuggestedBinding.type(XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING);
            profileSuggestedBinding.interactionProfile(interactionProfile);
            profileSuggestedBinding.suggestedBindings(suggestedBindings);

            xrCheck(xrSuggestInteractionProfileBindings(xrInstance, profileSuggestedBinding), "SuggestInteractionProfileBindings");

            XrActionSpaceCreateInfo ciActionSpaceLeft = XrActionSpaceCreateInfo.callocStack(stack);
            ciActionSpaceLeft.type(XR_TYPE_ACTION_SPACE_CREATE_INFO);
            ciActionSpaceLeft.action(xrHandAction);
            ciActionSpaceLeft.subactionPath(leftHandPath);
            ciActionSpaceLeft.poseInActionSpace(identityPose(stack));

            XrActionSpaceCreateInfo ciActionSpaceRight = XrActionSpaceCreateInfo.callocStack(stack);
            ciActionSpaceRight.type(XR_TYPE_ACTION_SPACE_CREATE_INFO);
            ciActionSpaceRight.action(xrHandAction);
            ciActionSpaceRight.subactionPath(rightHandPath);
            ciActionSpaceRight.poseInActionSpace(identityPose(stack));

            PointerBuffer pActionSpaceLeft = stack.callocPointer(1);
            PointerBuffer pActionSpaceRight = stack.callocPointer(1);
            xrCheck(xrCreateActionSpace(xrVkSession, ciActionSpaceLeft, pActionSpaceLeft), "CreateActionSpace");
            xrCheck(xrCreateActionSpace(xrVkSession, ciActionSpaceRight, pActionSpaceRight), "CreateActionSpace");
            this.xrLeftHandSpace = new XrSpace(pActionSpaceLeft.get(0), xrVkSession);
            this.xrRightHandSpace = new XrSpace(pActionSpaceRight.get(0), xrVkSession);

            XrSessionActionSetsAttachInfo aiActionSets = XrSessionActionSetsAttachInfo.callocStack(stack);
            aiActionSets.type(XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO);
            aiActionSets.actionSets(stack.pointers(xrActionSet));

            xrCheck(xrAttachSessionActionSets(xrVkSession, aiActionSets), "AttachSessionActionSets");
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

            // NOTE: The call to memAddress is important because LWJGL doesn't expect byte buffers to end with the null char
            String[] xrVkInstanceExtensions = memUTF8(memAddress(pXrVkInstanceExtensions)).split(" ");

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
            // NOTE: The memAddress call is important because LWJGL doesn't expect byte buffers to contain the 0 char
            String[] xrDeviceExtensions = memUTF8(memAddress(pXrDeviceExtensions)).split(" ");

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

        if (this.vkRenderPass != 0) {
            vkDestroyRenderPass(vkDevice, vkRenderPass, null);
        }

        if (vkPipelineLayout != 0) {
            vkDestroyPipelineLayout(vkDevice, vkPipelineLayout, null);
        }

        if (vkGraphicsPipelines != null) {
            for (int pipelineIndex = 0; pipelineIndex < vkGraphicsPipelines.length; pipelineIndex++) {
                long graphicsPipeline = vkGraphicsPipelines[pipelineIndex];
                if (graphicsPipeline != 0 && (pipelineIndex == 0 || graphicsPipeline != vkGraphicsPipelines[pipelineIndex - 1])) {
                    vkDestroyPipeline(vkDevice, graphicsPipeline, null);
                }
            }
        }

        if (vkCommandPool != 0) {
            vkDestroyCommandPool(vkDevice, vkCommandPool, null);
        }

        if (vkBigBuffer != 0) {
            vkDestroyBuffer(vkDevice, vkBigBuffer, null);
        }
        if (vkDeviceMemory != 0) {
            vkFreeMemory(vkDevice, vkDeviceMemory, null);
        }

        if (vkDevice != null) {
            vkDestroyDevice(vkDevice, null);
        }

        if (vkDebugMessenger != 0L) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, vkDebugMessenger, null);
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
        chooseSwapchainFormat();
        createRenderPass();
        createSwapchains();
        createCommandPools();
        createGraphicsPipelines();
        createRenderSpace();
        createBuffers();
    }

    private int chooseMemoryTypeIndex(VkMemoryRequirements requirements, int requiredPropertyBits, MemoryStack stack) {

        VkPhysicalDeviceMemoryProperties memoryProps = VkPhysicalDeviceMemoryProperties.callocStack(stack);
        vkGetPhysicalDeviceMemoryProperties(vkPhysicalDevice, memoryProps);

        for (int memoryTypeIndex = 0; memoryTypeIndex < memoryProps.memoryTypeCount(); memoryTypeIndex++) {

            boolean allowedByResource = (requirements.memoryTypeBits() & (1 << memoryTypeIndex)) != 0;
            boolean hasNeededProperties = (memoryProps.memoryTypes(memoryTypeIndex).propertyFlags() & requiredPropertyBits) == requiredPropertyBits;

            if (allowedByResource && hasNeededProperties) {
                return memoryTypeIndex;
            }
        }

        throw new IllegalArgumentException("Failed to find suitable memory type for resource");
    }

    private static final float[][] QUAD_OFFSETS = {
        {-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}
    };

    private static class CubePlane {

        final float constantX, constantY, constantZ;
        final float factorX, factorY, factorZ;
        final int offsetX, offsetY, offsetZ;
        final int colorIndex;

        CubePlane(
            float constantX, float constantY, float constantZ,
            float factorX, float factorY, float factorZ,
            int offsetX, int offsetY, int offsetZ, int colorIndex
        ) {
            this.constantX = constantX;
            this.constantY = constantY;
            this.constantZ = constantZ;

            this.factorX = factorX;
            this.factorY = factorY;
            this.factorZ = factorZ;

            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;

            this.colorIndex = colorIndex;
        }
    }

    private static final CubePlane[] CUBE_PLANES = {
        // Bottom
        new CubePlane(
            0, -1, 0, 1, 0, -1,
            0, 2, 1, 0
        ),
        // Top
        new CubePlane(
            0, 1, 0, 1, 0, 1,
            0, 2, 1, 1
        ),
        // Left
        new CubePlane(
            -1, 0, 0, 0, 1, -1,
            2, 1, 0, 2
        ),
        // Right
        new CubePlane(
            1, 0, 0, 0, 1, 1,
            2, 1, 0, 3
        ),
        // Front
        new CubePlane(
            0, 0, -1, 1, 1, 0,
            0, 1, 2, 4
        ),
        // Back
        new CubePlane(
            0, 0, 1, -1, 1, 0,
            0, 1, 2, 5
        )
    };

    private static void putVertexData(ByteBuffer dest) {

        Random random = new Random(87234);
        for (int cubeIndex = 0; cubeIndex < BigModel.NUM_CUBES; cubeIndex++) {

            float midX = 60f * random.nextFloat() - 30f;
            float midY = 60f * random.nextFloat() - 30f;
            float midZ = 60f * random.nextFloat() - 30f;

            // Each side has its own color
            float[] red = new float[6];
            float[] green = new float[6];
            float[] blue = new float[6];

            for (int side = 0; side < 6; side++) {
                red[side] = random.nextFloat();
                green[side] = random.nextFloat();
                blue[side] = random.nextFloat();
            }

            float size = 1.5f;

            for (CubePlane plane : CUBE_PLANES) {
                for (float[] offsets : QUAD_OFFSETS) {
                    float cornerX = midX + size * (plane.constantX + offsets[plane.offsetX] * plane.factorX);
                    float cornerY = midY + size * (plane.constantY + offsets[plane.offsetY] * plane.factorY);
                    float cornerZ = midZ + size * (plane.constantZ + offsets[plane.offsetZ] * plane.factorZ);
                    dest.putFloat(cornerX);
                    dest.putFloat(cornerY);
                    dest.putFloat(cornerZ);
                    dest.putFloat(red[plane.colorIndex]);
                    dest.putFloat(green[plane.colorIndex]);
                    dest.putFloat(blue[plane.colorIndex]);
                }
            }
        }

        // 1 more cube for the small model
        float size = 0.15f;
        for (CubePlane plane : CUBE_PLANES) {
            for (float[] offsets : QUAD_OFFSETS) {
                float cornerX = size * (plane.constantX + offsets[plane.offsetX] * plane.factorX);
                float cornerY = size * (plane.constantY + offsets[plane.offsetY] * plane.factorY);
                float cornerZ = size * (plane.constantZ + offsets[plane.offsetZ] * plane.factorZ);
                dest.putFloat(cornerX);
                dest.putFloat(cornerY);
                dest.putFloat(cornerZ);
                dest.putFloat(max(0f, abs(plane.constantX)));
                dest.putFloat(max(0f, abs(plane.constantY)));
                dest.putFloat(max(0f, abs(plane.constantZ)));
            }
        }
    }

    private static void putIndexData(ByteBuffer dest) {

        int vertexIndex = 0;

        for (int cubeCounter = 0; cubeCounter < BigModel.NUM_CUBES; cubeCounter++) {
            for (int quadCounter = 0; quadCounter < 6; quadCounter++) {

                int[] indexOffsets = {0, 1, 2, 2, 3, 0};
                for (int indexOffset : indexOffsets) {
                    if (INDEX_SIZE == 4) {
                        dest.putInt(vertexIndex + indexOffset);
                    } else if (INDEX_SIZE == 2) {
                        dest.putShort((short) (vertexIndex + indexOffset));
                    } else {
                        throw new Error("Unexpected index size: " + INDEX_SIZE);
                    }
                }

                vertexIndex += 4;
            }
        }
    }

    private void createBuffers() {
        try (MemoryStack stack = stackPush()) {

            int bigVertexSize = BigModel.NUM_VERTICES * VERTEX_SIZE;
            int bigIndexSize = BigModel.NUM_INDICES * INDEX_SIZE;

            // The small model only needs 1 cube
            int smallVertexSize = 6 * 4 * VERTEX_SIZE;
            // We can just reuse the indices of the big model because the ones for the small model should be a substring of it

            int totalVertexSize = bigVertexSize + smallVertexSize;

            long sharedBuffer = createBuffer(totalVertexSize + bigIndexSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

            VkMemoryRequirements sharedMemRequirements = VkMemoryRequirements.callocStack(stack);
            vkGetBufferMemoryRequirements(vkDevice, sharedBuffer, sharedMemRequirements);

            VkMemoryAllocateInfo aiSharedMemory = VkMemoryAllocateInfo.callocStack(stack);
            aiSharedMemory.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            aiSharedMemory.allocationSize(sharedMemRequirements.size());
            aiSharedMemory.memoryTypeIndex(chooseMemoryTypeIndex(sharedMemRequirements,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, stack));

            LongBuffer pSharedMemory = stack.callocLong(1);
            vkCheck(vkAllocateMemory(vkDevice, aiSharedMemory, null, pSharedMemory), "AllocateMemory");
            long sharedMemory = pSharedMemory.get(0);

            PointerBuffer ppSharedData = stack.callocPointer(1);
            vkCheck(vkMapMemory(vkDevice, sharedMemory, 0, VK_WHOLE_SIZE, 0, ppSharedData), "MapMemory");

            ByteBuffer sharedData = memByteBuffer(ppSharedData.get(0), totalVertexSize + bigIndexSize);
            sharedData.position(0);
            sharedData.limit(totalVertexSize);
            putVertexData(sharedData);
            sharedData.position(totalVertexSize);
            sharedData.limit(totalVertexSize + bigIndexSize);
            putIndexData(sharedData);
            sharedData.position(0);
            sharedData.limit(sharedData.capacity());

            VkMappedMemoryRange sharedMemoryRange = VkMappedMemoryRange.callocStack(stack);
            sharedMemoryRange.sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE);
            sharedMemoryRange.memory(sharedMemory);
            sharedMemoryRange.offset(0);
            sharedMemoryRange.size(VK_WHOLE_SIZE);

            vkCheck(vkFlushMappedMemoryRanges(vkDevice, sharedMemoryRange), "FlushMappedMemoryRanges");
            vkUnmapMemory(vkDevice, sharedMemory);
            vkCheck(vkBindBufferMemory(vkDevice, sharedBuffer, sharedMemory, 0), "BindBufferMemory");

            this.vkBigBuffer = createBuffer(
                totalVertexSize + bigIndexSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
            );

            VkMemoryRequirements deviceMemRequirements = VkMemoryRequirements.callocStack(stack);
            vkGetBufferMemoryRequirements(vkDevice, vkBigBuffer, deviceMemRequirements);

            VkMemoryAllocateInfo aiDeviceMemory = VkMemoryAllocateInfo.callocStack(stack);
            aiDeviceMemory.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            aiDeviceMemory.allocationSize(deviceMemRequirements.size());
            aiDeviceMemory.memoryTypeIndex(chooseMemoryTypeIndex(deviceMemRequirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));

            LongBuffer pDeviceMemory = stack.callocLong(1);
            vkCheck(vkAllocateMemory(vkDevice, aiSharedMemory, null, pDeviceMemory), "AllocateMemory");
            this.vkDeviceMemory = pDeviceMemory.get(0);

            vkCheck(vkBindBufferMemory(vkDevice, vkBigBuffer, vkDeviceMemory, 0), "BindBufferMemory");

            VkCommandBufferAllocateInfo aiCommandBuffer = VkCommandBufferAllocateInfo.callocStack(stack);
            aiCommandBuffer.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            aiCommandBuffer.commandPool(vkCommandPool);
            aiCommandBuffer.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            aiCommandBuffer.commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.callocPointer(1);
            vkCheck(vkAllocateCommandBuffers(vkDevice, aiCommandBuffer, pCommandBuffer), "AllocateCommandBuffers");
            VkCommandBuffer copyCommandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), vkDevice);

            VkCommandBufferBeginInfo biCommandBuffer = VkCommandBufferBeginInfo.callocStack(stack);
            biCommandBuffer.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            biCommandBuffer.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkCheck(vkBeginCommandBuffer(copyCommandBuffer, biCommandBuffer), "BeginCommandBuffer");

            VkBufferCopy.Buffer copyRegions = VkBufferCopy.callocStack(1, stack);
            VkBufferCopy copyRegion = copyRegions.get(0);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(totalVertexSize + bigIndexSize);

            vkCmdCopyBuffer(copyCommandBuffer, sharedBuffer, vkBigBuffer, copyRegions);
            vkEndCommandBuffer(copyCommandBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.callocStack(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.waitSemaphoreCount(0); // I'm not sure why I need to set this explicitly
            submitInfo.pWaitSemaphores(null);
            submitInfo.pSignalSemaphores(null);
            submitInfo.pWaitDstStageMask(null);
            submitInfo.pCommandBuffers(stack.pointers(copyCommandBuffer.address()));

            VkFenceCreateInfo ciFence = VkFenceCreateInfo.callocStack(stack);
            ciFence.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            ciFence.flags(0);

            LongBuffer pFence = stack.callocLong(1);
            vkCheck(vkCreateFence(vkDevice, ciFence, null, pFence), "CreateFence");
            long fence = pFence.get(0);

            vkCheck(vkQueueSubmit(vkQueue, submitInfo, fence), "QueueSubmit");
            vkCheck(vkWaitForFences(vkDevice, pFence, true, 1_000_000_000L), "WaitForFences");
            vkCheck(vkQueueWaitIdle(vkQueue), "QueueWaitIdle");

            vkFreeMemory(vkDevice, sharedMemory, null);
            vkDestroyBuffer(vkDevice, sharedBuffer, null);
            vkFreeCommandBuffers(vkDevice, vkCommandPool, copyCommandBuffer);
            vkDestroyFence(vkDevice, fence, null);
        }
    }

    private long createBuffer(long size, int usage) {

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo ciBuffer = VkBufferCreateInfo.callocStack(stack);
            ciBuffer.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            ciBuffer.flags(0);
            ciBuffer.size(size);
            ciBuffer.usage(usage);
            ciBuffer.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.callocLong(1);
            vkCreateBuffer(vkDevice, ciBuffer, null, pBuffer);
            return pBuffer.get(0);
        }
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
            // TODO SAMPLES
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentDescription depthAttachment = attachments.get(1);
            depthAttachment.flags(0);
            depthAttachment.format(DEPTH_FORMAT);
            // TODO SAMPLES
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

            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.callocStack(1, stack);
            VkPushConstantRange pushConstant = pushConstants.get(0);
            pushConstant.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            pushConstant.offset(0);
            // 1 matrix of 4x4 floats (4 bytes per float)
            // and 1 boolean that needs 4 bytes
            pushConstant.size(4 * 4 * 4 + 4);

            VkPipelineLayoutCreateInfo ciPipelineLayout = VkPipelineLayoutCreateInfo.callocStack(stack);
            ciPipelineLayout.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            ciPipelineLayout.flags(0);
            // I will add uniform variables or push constants later
            ciPipelineLayout.pSetLayouts(null);
            ciPipelineLayout.pPushConstantRanges(pushConstants);

            LongBuffer pPipelineLayout = stack.callocLong(1);
            vkCheck(vkCreatePipelineLayout(vkDevice, ciPipelineLayout, null, pPipelineLayout), "CreatePipelineLayout");
            this.vkPipelineLayout = pPipelineLayout.get(0);
        }

        long vertexModule, fragmentModule;
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

        this.vkGraphicsPipelines = new long[this.swapchains.length];

        for (int swapchainIndex = 0; swapchainIndex < this.swapchains.length; swapchainIndex++) {
            if (
                swapchainIndex == 0 || this.swapchains[swapchainIndex - 1].width != this.swapchains[swapchainIndex].width
                || this.swapchains[swapchainIndex - 1].height != this.swapchains[swapchainIndex].height
            ) {
                createGraphicsPipeline(swapchainIndex, vertexModule, fragmentModule);
            } else {
                // If the swapchain has the same size as the previous swapchain (expected case), share the graphics pipeline
                this.vkGraphicsPipelines[swapchainIndex] = this.vkGraphicsPipelines[swapchainIndex - 1];
            }
        }

        vkDestroyShaderModule(vkDevice, vertexModule, null);
        vkDestroyShaderModule(vkDevice, fragmentModule, null);
    }

    private void createGraphicsPipeline(int swapchainIndex, long vertexModule, long fragmentModule) {

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
            ciVertexInput.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
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
            ciRasterizationState.lineWidth(1f);
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
            // TODO SAMPLES
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
            ciPipeline.subpass(0);
            ciPipeline.layout(this.vkPipelineLayout);
            ciPipeline.basePipelineHandle(VK_NULL_HANDLE); // No pipeline derivatives in this example
            ciPipeline.basePipelineIndex(-1);
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
            ciPipeline.pDynamicState(null); // No dynamic state will be needed

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

    private void chooseSwapchainFormat() {
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
        }
    }

    private void createSwapchains() {
        try (MemoryStack stack = stackPush()) {

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
                XrSwapchainImageVulkanKHR.Buffer swapchainColorImages = XrSwapchainImageVulkanKHR.callocStack(numImages, stack);
                for (int imageIndex = 0; imageIndex < numImages; imageIndex++) {
                    swapchainColorImages.get(imageIndex).type(XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR);
                }
                xrCheck(xrEnumerateSwapchainImages(
                    swapchain, pNumImages,
                    XrSwapchainImageBaseHeader.create(swapchainColorImages.address(), swapchainColorImages.capacity())
                ), "EnumerateSwapchainImages");

                VkImageCreateInfo ciDepthImage = VkImageCreateInfo.callocStack(stack);
                ciDepthImage.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
                ciDepthImage.flags(0);
                ciDepthImage.imageType(VK_IMAGE_TYPE_2D);
                ciDepthImage.format(DEPTH_FORMAT);
                ciDepthImage.extent().width(ciSwapchain.width());
                ciDepthImage.extent().height(ciSwapchain.height());
                ciDepthImage.extent().depth(1);
                ciDepthImage.mipLevels(1);
                ciDepthImage.arrayLayers(1);
                // TODO SAMPLES
                ciDepthImage.samples(VK_SAMPLE_COUNT_1_BIT);
                ciDepthImage.tiling(VK_IMAGE_TILING_OPTIMAL);
                ciDepthImage.usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
                ciDepthImage.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                ciDepthImage.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

                LongBuffer pDepthImage = stack.callocLong(1);
                vkCheck(vkCreateImage(vkDevice, ciDepthImage, null, pDepthImage), "CreateImage");
                long depthImage = pDepthImage.get(0);

                SwapchainImage[] swapchainImages = new SwapchainImage[numImages];

                VkComponentMapping componentMapping = VkComponentMapping.callocStack(stack);
                componentMapping.r(VK_COMPONENT_SWIZZLE_IDENTITY);
                componentMapping.g(VK_COMPONENT_SWIZZLE_IDENTITY);
                componentMapping.b(VK_COMPONENT_SWIZZLE_IDENTITY);
                componentMapping.a(VK_COMPONENT_SWIZZLE_IDENTITY);

                VkImageSubresourceRange colorSubresourceRange = VkImageSubresourceRange.callocStack(stack);
                colorSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                colorSubresourceRange.baseMipLevel(0);
                colorSubresourceRange.levelCount(1);
                colorSubresourceRange.baseArrayLayer(0);
                colorSubresourceRange.layerCount(1);

                VkImageSubresourceRange depthSubresourceRange = VkImageSubresourceRange.callocStack(stack);
                depthSubresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                depthSubresourceRange.baseMipLevel(0);
                depthSubresourceRange.levelCount(1);
                depthSubresourceRange.baseArrayLayer(0);
                depthSubresourceRange.layerCount(1);

                VkImageViewCreateInfo ciDepthImageView = VkImageViewCreateInfo.callocStack(stack);
                ciDepthImageView.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                ciDepthImageView.flags(0);
                ciDepthImageView.image(depthImage);
                ciDepthImageView.viewType(VK_IMAGE_VIEW_TYPE_2D);
                ciDepthImageView.format(DEPTH_FORMAT);
                ciDepthImageView.components(componentMapping);
                ciDepthImageView.subresourceRange(depthSubresourceRange);

                VkMemoryRequirements pMemoryRequirements = VkMemoryRequirements.callocStack(stack);
                vkGetImageMemoryRequirements(vkDevice, depthImage, pMemoryRequirements);

                int memoryTypeIndex = chooseMemoryTypeIndex(pMemoryRequirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);

                VkMemoryAllocateInfo aiDepthMemory = VkMemoryAllocateInfo.callocStack(stack);
                aiDepthMemory.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                aiDepthMemory.allocationSize(pMemoryRequirements.size());
                aiDepthMemory.memoryTypeIndex(memoryTypeIndex);

                LongBuffer pDepthMemory = stack.callocLong(1);
                vkCheck(vkAllocateMemory(vkDevice, aiDepthMemory, null, pDepthMemory), "AllocateMemory");
                long depthImageMemory = pDepthMemory.get(0);

                vkCheck(vkBindImageMemory(vkDevice, depthImage, depthImageMemory, 0), "BindImageMemory");

                LongBuffer pDepthImageView = stack.callocLong(1);
                vkCheck(vkCreateImageView(vkDevice, ciDepthImageView, null, pDepthImageView), "CreateImageView");
                long depthImageView = pDepthImageView.get(0);

                for (int imageIndex = 0; imageIndex < numImages; imageIndex++) {

                    long colorImage = swapchainColorImages.get(imageIndex).image();

                    VkImageViewCreateInfo ciImageView = VkImageViewCreateInfo.callocStack(stack);
                    ciImageView.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                    ciImageView.flags(0);
                    ciImageView.image(colorImage);
                    ciImageView.viewType(VK_IMAGE_VIEW_TYPE_2D);
                    ciImageView.format(swapchainFormat);
                    ciImageView.components(componentMapping);
                    ciImageView.subresourceRange(colorSubresourceRange);

                    LongBuffer pImageView = stack.callocLong(1);
                    vkCheck(vkCreateImageView(vkDevice, ciImageView, null, pImageView), "CreateImageView");
                    long colorImageView = pImageView.get(0);

                    VkFramebufferCreateInfo ciFramebuffer = VkFramebufferCreateInfo.callocStack(stack);
                    ciFramebuffer.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                    ciFramebuffer.flags(0);
                    ciFramebuffer.renderPass(vkRenderPass);
                    ciFramebuffer.pAttachments(stack.longs(colorImageView, depthImageView));
                    ciFramebuffer.width(ciSwapchain.width());
                    ciFramebuffer.height(ciSwapchain.height());
                    ciFramebuffer.layers(1);

                    LongBuffer pFramebuffer = stack.callocLong(1);
                    vkCheck(vkCreateFramebuffer(vkDevice, ciFramebuffer, null, pFramebuffer), "CreateFramebuffer");
                    long framebuffer = pFramebuffer.get(0);

                    swapchainImages[imageIndex] = new SwapchainImage(colorImage, colorImageView, framebuffer);
                }

                this.swapchains[swapchainIndex] = new SwapchainWrapper(
                    new XrSwapchain(pSwapchain.get(0), xrVkSession),
                    ciSwapchain.width(), ciSwapchain.height(), ciSwapchain.sampleCount(),
                    depthImage, depthImageView, depthImageMemory, swapchainImages
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

    private static class HandStates {

        final Vector3f leftPosition, rightPosition;
        final Quaternionf leftRotation, rightRotation;

        HandStates(Vector3f leftPosition, Vector3f rightPosition, Quaternionf leftRotation, Quaternionf rightRotation) {
            this.leftPosition = leftPosition;
            this.rightPosition = rightPosition;
            this.leftRotation = leftRotation;
            this.rightRotation = rightRotation;
        }
    }

    private HandStates getHandStates(MemoryStack stack, long time) {

        XrActiveActionSet.Buffer activeActionSets = XrActiveActionSet.callocStack(1, stack);
        XrActiveActionSet activeActionSet = activeActionSets.get(0);
        activeActionSet.actionSet(xrActionSet);
        activeActionSet.subactionPath(XR_NULL_PATH);

        XrActionsSyncInfo siActions = XrActionsSyncInfo.callocStack(stack);
        siActions.type(XR_TYPE_ACTIONS_SYNC_INFO);
        siActions.activeActionSets(activeActionSets);

        xrCheck(xrSyncActions(xrVkSession, siActions), "SyncActions");

        XrSpaceLocation leftHandLocation = XrSpaceLocation.callocStack(stack);
        leftHandLocation.type(XR_TYPE_SPACE_LOCATION);

        XrSpaceLocation rightHandLocation = XrSpaceLocation.callocStack(stack);
        rightHandLocation.type(XR_TYPE_SPACE_LOCATION);

        xrCheck(xrLocateSpace(xrLeftHandSpace, renderSpace, time, leftHandLocation), "LocateSpace");
        xrCheck(xrLocateSpace(xrRightHandSpace, renderSpace, time, rightHandLocation), "LocateSpace");

        Vector3f leftHandPosition = null;
        Vector3f rightHandPosition = null;

        if ((leftHandLocation.locationFlags() & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) {
            XrVector3f pos = leftHandLocation.pose().position$();
            leftHandPosition = new Vector3f(pos.x(), pos.y(), pos.z());
        }
        if ((rightHandLocation.locationFlags() & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) {
            XrVector3f pos = rightHandLocation.pose().position$();
            rightHandPosition = new Vector3f(pos.x(), pos.y(), pos.z());
        }

        Quaternionf leftHandRotation = null;
        Quaternionf rightHandRotation = null;

        if ((leftHandLocation.locationFlags() & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
            XrQuaternionf rot = leftHandLocation.pose().orientation();
            leftHandRotation = new Quaternionf(rot.x(), rot.y(), rot.z(), rot.w());
        }

        if ((rightHandLocation.locationFlags() & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
            XrQuaternionf rot = rightHandLocation.pose().orientation();
            rightHandRotation = new Quaternionf(rot.x(), rot.y(), rot.z(), rot.w());
        }

        return new HandStates(leftHandPosition, rightHandPosition, leftHandRotation, rightHandRotation);
    }

    private void loopXrSession() {

        // This is a safety check for debugging. Set to 0 to disable this.
        long endTime = System.currentTimeMillis() + 10_000;

        boolean startedSession = false;

        while (true) {
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

                        HandStates handStates = getHandStates(stack, frameState.predictedDisplayTime());

                        // TODO Figure out which of the two gets computed correctly
                        Matrix4f leftHandMatrix = null;
                        if (handStates.leftPosition != null) {
                            leftHandMatrix = new Matrix4f().translate(handStates.leftPosition);

                            if (handStates.leftRotation != null) {
                                leftHandMatrix.rotate(handStates.leftRotation);
                            }
                        }

                        Matrix4f rightHandMatrix = null;
                        if (handStates.rightPosition != null) {
                            rightHandMatrix = new Matrix4f();

                            if (handStates.rightRotation != null) {
                                rightHandMatrix.rotate(handStates.rightRotation);
                            }

                            rightHandMatrix.translate(handStates.rightPosition);
                        }

                        for (int swapchainIndex = 0; swapchainIndex < swapchains.length; swapchainIndex++) {
                            SwapchainWrapper swapchain = this.swapchains[swapchainIndex];
                            IntBuffer pImageIndex = stack.callocInt(1);
                            int acquireResult = xrAcquireSwapchainImage(swapchain.swapchain, null, pImageIndex);
                            if (acquireResult != XR_SESSION_LOSS_PENDING) {
                                xrCheck(acquireResult, "AcquireSwapchainImage");
                            }

                            Matrix4f cameraMatrix;
                            if (projectionViews != null) {

                                // If the position tracker is working, we should use it to create the camera matrix
                                XrCompositionLayerProjectionView projectionView = projectionViews.get(swapchainIndex);
                                Matrix4f projectionMatrix = new Matrix4f();
                                projectionMatrix.set(XRHelper.createProjectionMatrixBuffer(stack, projectionView.fov(), 0.01f, 100f, true));

                                Matrix4f viewMatrix = new Matrix4f();
                                XrVector3f position = projectionView.pose().position$();
                                XrQuaternionf orientation = projectionView.pose().orientation();
                                viewMatrix.translationRotateScaleInvert(
                                    position.x(), position.y(), position.z(),
                                    orientation.x(), orientation.y(), orientation.z(), orientation.w(),
                                    1, 1, 1
                                );

                                cameraMatrix = projectionMatrix.mul(viewMatrix);
                            } else {
                                // When the position tracking is having issues, we will fall back to the previous one
                                cameraMatrix = lastCameraMatrix;
                            }

                            lastCameraMatrix = cameraMatrix;

                            int imageIndex = pImageIndex.get(0);

                            XrSwapchainImageWaitInfo wiSwapchainImage = XrSwapchainImageWaitInfo.callocStack(stack);
                            wiSwapchainImage.type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO);
                            // Time out after 1 second. If we would have to wait so long, something is seriously wrong
                            wiSwapchainImage.timeout(1_000_000_000);

                            int waitImageResult = xrWaitSwapchainImage(swapchain.swapchain, wiSwapchainImage);
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

                            VkClearColorValue clearColorValue = VkClearColorValue.callocStack(stack);
                            clearColorValue.float32(0, 0.1f);
                            clearColorValue.float32(1, 0.2f);
                            clearColorValue.float32(2, 0.92f);
                            clearColorValue.float32(3, 1f);

                            VkClearDepthStencilValue clearDepthValue = VkClearDepthStencilValue.callocStack(stack);
                            clearDepthValue.depth(1f);

                            VkClearValue.Buffer clearValues = VkClearValue.callocStack(2, stack);
                            clearValues.get(0).color(clearColorValue);
                            clearValues.get(1).depthStencil(clearDepthValue);

                            VkRenderPassBeginInfo biRenderPass = VkRenderPassBeginInfo.callocStack(stack);
                            biRenderPass.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
                            biRenderPass.pNext(VK_NULL_HANDLE);
                            biRenderPass.renderPass(vkRenderPass);
                            biRenderPass.framebuffer(swapchain.images[imageIndex].framebuffer);
                            biRenderPass.renderArea(VkRect2D.callocStack(stack).set(
                                VkOffset2D.callocStack(stack).set(0, 0),
                                VkExtent2D.callocStack(stack).set(swapchain.width, swapchain.height)
                            ));
                            biRenderPass.pClearValues(clearValues);

                            ByteBuffer pushConstants = stack.calloc(4 * 4 * 4 + 4);
                            cameraMatrix.get(pushConstants);

                            int stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                            vkCmdBeginRenderPass(commandBuffer, biRenderPass, VK_SUBPASS_CONTENTS_INLINE);
                            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, vkGraphicsPipelines[swapchainIndex]);
                            vkCmdPushConstants(commandBuffer, vkPipelineLayout, stageFlags, 0, pushConstants);
                            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vkBigBuffer), stack.longs(0));
                            vkCmdBindIndexBuffer(commandBuffer, vkBigBuffer, VERTEX_SIZE * (BigModel.NUM_VERTICES + 4 * 6), INDEX_TYPE);

                            // Draw big model
                            vkCmdDrawIndexed(commandBuffer, BigModel.NUM_INDICES, 1, 0, 0, 0);

                            // Draw (small) hand models if hand locations are known
                            // TODO Invert the colors if the corresponding hand trigger is pressed
                            if (leftHandMatrix != null) {
                                cameraMatrix.mul(leftHandMatrix, new Matrix4f()).get(pushConstants);
                                vkCmdPushConstants(commandBuffer, vkPipelineLayout, stageFlags, 0, pushConstants);
                                vkCmdDrawIndexed(commandBuffer, 6 * 6, 1, 0, BigModel.NUM_VERTICES, 0);
                            }

                            if (rightHandMatrix != null) {
                                cameraMatrix.mul(rightHandMatrix, new Matrix4f()).get(pushConstants);
                                vkCmdPushConstants(commandBuffer, vkPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants);
                                vkCmdDrawIndexed(commandBuffer, 6 * 6, 1, 0, BigModel.NUM_VERTICES, 0);
                            }

                            vkCmdEndRenderPass(commandBuffer);

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

                            int releaseResult = xrReleaseSwapchainImage(swapchain.swapchain, null);
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

                    for (SwapchainImage swapchainImage : swapchain.images) {
                        vkDestroyFramebuffer(vkDevice, swapchainImage.framebuffer, null);
                        vkDestroyImageView(vkDevice, swapchainImage.colorImageView, null);
                        // No need to destroy the colorImage because that happens implicitly upon destroying swapchain
                    }

                    vkDestroyImageView(vkDevice, swapchain.depthImageView, null);
                    vkDestroyImage(vkDevice, swapchain.depthImage, null);
                    vkFreeMemory(vkDevice, swapchain.depthImageMemory, null);
                }
            }
        }
    }

    private void destroyXrVkSession() {
        if (xrHandAction != null) {
            xrDestroyAction(xrHandAction);
        }
        if (xrActionSet != null) {
            xrDestroyActionSet(xrActionSet);
        }
        if (xrLeftHandSpace != null) {
            xrDestroySpace(xrLeftHandSpace);
        }
        if (xrRightHandSpace != null) {
            xrDestroySpace(xrRightHandSpace);
        }
        if (renderSpace != null) {
            xrDestroySpace(renderSpace);
        }
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
