// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime;

import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import java.util.List;

/** The set of options for the Skyfocus feature. */
public final class SkyfocusOptions extends OptionsBase {

  @Option(
      name = "experimental_enable_skyfocus",
      defaultValue = "false",
      effectTags = OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS,
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      help =
          "If true, enable the use of --experimental_working_set to reduce Bazel's memory footprint"
              + " for incremental builds. This feature is known as Skyfocus.")
  public boolean skyfocusEnabled;

  @Option(
      name = "experimental_working_set",
      defaultValue = "",
      effectTags = OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS,
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      converter = Converters.CommaSeparatedOptionListConverter.class,
      help =
          "The working set for Skyfocus. Specify as comma-separated workspace root-relative paths."
              + " This is a stateful flag. Defining a working set persists it for subsequent"
              + " invocations, until it is redefined with a new set.")
  public List<String> workingSet;

  @Option(
      name = "experimental_skyfocus_dump_keys",
      defaultValue = "none",
      effectTags = OptionEffectTag.TERMINAL_OUTPUT,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      converter = SkyfocusDumpEnumConverter.class,
      help =
          "For debugging Skyfocus. Dump the focused SkyKeys (roots, leafs, focused deps, focused"
              + " rdeps).")
  public SkyfocusDumpOption dumpKeys;

  @Option(
      name = "experimental_skyfocus_dump_post_gc_stats",
      defaultValue = "false",
      effectTags = OptionEffectTag.TERMINAL_OUTPUT,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      help =
          "For debugging Skyfocus. If enabled, trigger manual GC before/after focusing to report"
              + " heap sizes reductions. This will increase the Skyfocus latency.")
  public boolean dumpPostGcStats;

  /** Options to dump the state of the Skyframe graph before/after Skyfocus. */
  public enum SkyfocusDumpOption {
    NONE,

    /**
     * Dump the counts and reductions of SkyKeys in the graph, working set, and verification set.
     */
    COUNT,

    /** Dump the string representation of SkyKeys in the working set and verification set. */
    VERBOSE,
  }

  /** Enum converter for SkyframeDumpOption. */
  private static class SkyfocusDumpEnumConverter extends EnumConverter<SkyfocusDumpOption> {
    public SkyfocusDumpEnumConverter() {
      super(SkyfocusDumpOption.class, "Skyframe Dump option");
    }
  }
}
