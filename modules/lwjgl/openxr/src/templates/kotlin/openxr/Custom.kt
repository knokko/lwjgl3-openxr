/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package openxr

import openxr.templates.*
import org.lwjgl.generator.*

/**
 * Includes #defines that are not generated by vulkangen
 */
fun templateCustomization() {
    XR10.apply {
        macro(expression = "((major & 0xffffL) << 48) | ((minor & 0xffffL) << 32) | patch")..uint64_t(
            "XR_MAKE_VERSION",
            """
            Constructs an API version number.

            This macro <b>can</b> be used when constructing the ##XrApplicationInfo{@code ::pname:apiVersion} parameter passed to #CreateInstance().
            """,

            uint32_t("major", "the major version number"),
            uint32_t("minor", "the minor version number"),
            uint32_t("patch", "the patch version number"),

            noPrefix = true
        )

        LongConstant(
            """
            XR_CURRENT_API_VERSION is the current version of the OpenXR API.
            """,

            "CURRENT_API_VERSION".."XR_MAKE_VERSION(1, 0, 14)"
        )

        IntConstant(
            "API Constants",

            "TRUE".."1",
            "FALSE".."0",
            "MAX_EXTENSION_NAME_SIZE".."128",
            "MAX_API_LAYER_NAME_SIZE".."256",
            "MAX_API_LAYER_DESCRIPTION_SIZE".."256",
            "MAX_SYSTEM_NAME_SIZE".."256",
            "MAX_APPLICATION_NAME_SIZE".."128",
            "MAX_ENGINE_NAME_SIZE".."128",
            "MAX_RUNTIME_NAME_SIZE".."128",
            "MAX_PATH_LENGTH".."256",
            "MAX_STRUCTURE_NAME_SIZE".."64",
            "MAX_RESULT_STRING_SIZE".."64",
            "MAX_GRAPHICS_APIS_SUPPORTED".."32",
            "MAX_ACTION_SET_NAME_SIZE".."64",
            "MAX_ACTION_NAME_SIZE".."64",
            "MAX_LOCALIZED_ACTION_SET_NAME_SIZE".."128",
            "MAX_LOCALIZED_ACTION_NAME_SIZE".."128",
            "MIN_COMPOSITION_LAYERS_SUPPORTED".."16"
        )

        LongConstant(
            "",
            "NULL_SYSTEM_ID".."0",
            "NULL_PATH".."0",
            "NO_DURATION".."0",
            "INFINITE_DURATION".."0x7fffffffffffffffL",
            "MIN_HAPTIC_DURATION".."-1",
            "FREQUENCY_UNSPECIFIED".."0"
        )

        LongConstant(
            """
            {@code XR_NULL_HANDLE} is a reserved value representing a non-valid object handle. It may be passed to and returned from API functions only when 
            specifically allowed.
            """,

            "NULL_HANDLE"..0L
        )

        macro(expression = "(version >>> 48) & 0xffffL")..uint64_t(
            "XR_VERSION_MAJOR",
            "Extracts the API major version number from a packed version number.",

            uint64_t("version", "the OpenXR API version"),

            noPrefix = true
        )

        macro(expression = "(version >>> 32) & 0xffffL")..uint64_t(
            "XR_VERSION_MINOR",
            "Extracts the API minor version number from a packed version number.",

            uint64_t("version", "the OpenXR API version"),

            noPrefix = true
        )

        macro(expression = "version & 0xffffffffL")..uint64_t(
            "XR_VERSION_PATCH",
            "Extracts the API patch version number from a packed version number.",

            uint64_t("version", "the OpenXR API version"),

            noPrefix = true
        )
    }
}