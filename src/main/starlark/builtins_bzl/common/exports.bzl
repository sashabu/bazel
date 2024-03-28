# Copyright 2020 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Exported builtins symbols that are not specific to OSS Bazel."""

load("@_builtins//:common/cc/cc_binary.bzl", "cc_binary")
load("@_builtins//:common/cc/cc_common.bzl", "cc_common")
load("@_builtins//:common/cc/cc_compilation_helper.bzl", "cc_compilation_helper")
load("@_builtins//:common/cc/cc_helper.bzl", "cc_helper")
load("@_builtins//:common/cc/cc_import.bzl", "cc_import")
load("@_builtins//:common/cc/cc_library.bzl", "cc_library")
load("@_builtins//:common/cc/cc_shared_library.bzl", "CcSharedLibraryInfo", "cc_shared_library")
load("@_builtins//:common/cc/cc_shared_library_hint_info.bzl", "CcSharedLibraryHintInfo")
load("@_builtins//:common/cc/cc_test.bzl", "cc_test")
load("@_builtins//:common/cc/cc_toolchain.bzl", "cc_toolchain")
load("@_builtins//:common/cc/cc_toolchain_alias.bzl", "cc_toolchain_alias")
load("@_builtins//:common/java/proto/java_lite_proto_library.bzl", "java_lite_proto_library")
load("@_builtins//:common/objc/j2objc_library.bzl", "j2objc_library")
load("@_builtins//:common/objc/objc_import.bzl", "objc_import")
load("@_builtins//:common/objc/objc_library.bzl", "objc_library")
load("@_builtins//:common/proto/proto_common.bzl", "proto_common_do_not_use")
load("@_builtins//:common/proto/proto_info.bzl", "ProtoInfo")
load("@_builtins//:common/proto/proto_lang_toolchain.bzl", "proto_lang_toolchain")
load("@_builtins//:common/python/providers.bzl", "PyCcLinkParamsProvider", "PyInfo", "PyRuntimeInfo")
load("@_builtins//:common/python/py_runtime_macro.bzl", "py_runtime")
load("@_builtins//:common/xcode/available_xcodes.bzl", "available_xcodes")
load("@_builtins//:common/xcode/xcode_version.bzl", "xcode_version")
load(":common/cc/fdo_prefetch_hints.bzl", "fdo_prefetch_hints")
load(":common/cc/fdo_profile.bzl", "fdo_profile")
load(":common/cc/memprof_profile.bzl", "memprof_profile")
load(":common/cc/propeller_optimize.bzl", "propeller_optimize")
load(":common/java/java_binary_deploy_jar.bzl", get_java_build_info = "get_build_info")
load(":common/java/java_common.bzl", "java_common")
load(":common/java/java_info.bzl", "JavaInfo", "JavaPluginInfo")
load(":common/java/java_package_configuration.bzl", "java_package_configuration")
load(":common/java/java_runtime.bzl", "java_runtime")
load(":common/java/java_toolchain.bzl", "java_toolchain")
load(":common/objc/apple_common.bzl", "apple_common")
load(":common/objc/objc_common.bzl", "objc_common")

exported_toplevels = {
    # This dummy symbol is not part of the public API; it is only used to test
    # that builtins injection is working properly. Its built-in value is
    # "original value".
    "_builtins_dummy": "overridden value",
    "CcSharedLibraryInfo": CcSharedLibraryInfo,
    "CcSharedLibraryHintInfo": CcSharedLibraryHintInfo,
    "proto_common_do_not_use": proto_common_do_not_use,
    "PyRuntimeInfo": PyRuntimeInfo,
    "PyInfo": PyInfo,
    "PyCcLinkParamsProvider": PyCcLinkParamsProvider,
    "ProtoInfo": ProtoInfo,
    "cc_common": cc_common,
    "+JavaPluginInfo": JavaPluginInfo,
    "+JavaInfo": JavaInfo,
    "java_common": java_common,
    "apple_common": apple_common,
}

# A list of Starlarkified native rules.
#
# * leading `+` means the Starlark rule is used by default, but can be overridden
#   on the Bazel command line
# * no leading symbol means the Starlark rule is used and can't be overridden
# * leading `-` means the Starlark rule exists, but is not used by default
exported_rules = {
    "cc_import": cc_import,
    "java_lite_proto_library": java_lite_proto_library,
    "objc_import": objc_import,
    "objc_library": objc_library,
    "j2objc_library": j2objc_library,
    "cc_shared_library": cc_shared_library,
    "cc_binary": cc_binary,
    "cc_test": cc_test,
    "cc_library": cc_library,
    "proto_lang_toolchain": proto_lang_toolchain,
    "py_runtime": py_runtime,
    "cc_toolchain_alias": cc_toolchain_alias,
    "cc_toolchain": cc_toolchain,
    "java_package_configuration": java_package_configuration,
    "java_toolchain": java_toolchain,
    "java_runtime": java_runtime,
    "fdo_prefetch_hints": fdo_prefetch_hints,
    "fdo_profile": fdo_profile,
    "memprof_profile": memprof_profile,
    "propeller_optimize": propeller_optimize,
    "xcode_version": xcode_version,
    "available_xcodes": available_xcodes,
}

# A list of Starlark functions callable from native rules implementation.
exported_to_java = {
    "j2objc_mapping_file_info_union": objc_common.j2objc_mapping_file_info_union,
    "j2objc_entry_class_info_union": objc_common.j2objc_entry_class_info_union,
    "init_cc_compilation_context": cc_compilation_helper.init_cc_compilation_context,
    "java_common": java_common,
    "get_build_info": get_java_build_info,
    "get_toolchain_global_make_variables": cc_helper.get_toolchain_global_make_variables,
}
