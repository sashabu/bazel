// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Objects;

/**
 * Wraps an {@link ActionLookupKey}. The evaluation of this SkyKey is the entry point of analyzing
 * the {@link ActionLookupKey} and executing the associated actions.
 */
public final class BuildDriverKey implements SkyKey {
  private final ActionLookupKey actionLookupKey;
  private final TopLevelArtifactContext topLevelArtifactContext;
  private final boolean explicitlyRequested;
  private final boolean skipIncompatibleExplicitTargets;
  private final boolean isTopLevelAspectDriver;

  private final boolean extraActionTopLevelOnly;

  // This key is created anew each build, so it's fine to carry this information here.
  private final boolean keepGoing;

  private BuildDriverKey(
      ActionLookupKey actionLookupKey,
      TopLevelArtifactContext topLevelArtifactContext,
      boolean explicitlyRequested,
      boolean skipIncompatibleExplicitTargets,
      boolean extraActionTopLevelOnly,
      boolean keepGoing,
      boolean isTopLevelAspectDriver) {
    this.actionLookupKey = actionLookupKey;
    this.topLevelArtifactContext = topLevelArtifactContext;
    this.explicitlyRequested = explicitlyRequested;
    this.skipIncompatibleExplicitTargets = skipIncompatibleExplicitTargets;
    this.isTopLevelAspectDriver = isTopLevelAspectDriver;
    this.extraActionTopLevelOnly = extraActionTopLevelOnly;
    this.keepGoing = keepGoing;
  }

  public static BuildDriverKey ofTopLevelAspect(
      ActionLookupKey actionLookupKey,
      TopLevelArtifactContext topLevelArtifactContext,
      boolean explicitlyRequested,
      boolean skipIncompatibleExplicitTargets,
      boolean extraActionTopLevelOnly,
      boolean keepGoing) {
    return new BuildDriverKey(
        actionLookupKey,
        topLevelArtifactContext,
        explicitlyRequested,
        skipIncompatibleExplicitTargets,
        extraActionTopLevelOnly,
        keepGoing,
        /* isTopLevelAspectDriver= */ true);
  }

  public static BuildDriverKey ofConfiguredTarget(
      ActionLookupKey actionLookupKey,
      TopLevelArtifactContext topLevelArtifactContext,
      boolean explicitlyRequested,
      boolean skipIncompatibleExplicitTargets,
      boolean extraActionTopLevelOnly,
      boolean keepGoing) {
    return new BuildDriverKey(
        actionLookupKey,
        topLevelArtifactContext,
        explicitlyRequested,
        skipIncompatibleExplicitTargets,
        extraActionTopLevelOnly,
        keepGoing,
        /* isTopLevelAspectDriver= */ false);
  }

  public TopLevelArtifactContext getTopLevelArtifactContext() {
    return topLevelArtifactContext;
  }

  public ActionLookupKey getActionLookupKey() {
    return actionLookupKey;
  }

  public boolean isExplicitlyRequested() {
    return explicitlyRequested;
  }

  public boolean shouldSkipIncompatibleExplicitTargets() {
    return skipIncompatibleExplicitTargets;
  }

  public boolean isTopLevelAspectDriver() {
    return isTopLevelAspectDriver;
  }

  public boolean isExtraActionTopLevelOnly() {
    return extraActionTopLevelOnly;
  }

  public boolean keepGoing() {
    return keepGoing;
  }

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.BUILD_DRIVER;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof BuildDriverKey) {
      BuildDriverKey otherBuildDriverKey = (BuildDriverKey) other;
      return actionLookupKey.equals(otherBuildDriverKey.actionLookupKey)
          && topLevelArtifactContext.equals(otherBuildDriverKey.topLevelArtifactContext)
          && explicitlyRequested == otherBuildDriverKey.explicitlyRequested;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(actionLookupKey, topLevelArtifactContext, explicitlyRequested);
  }

  @Override
  public String toString() {
    return String.format("BuildDriverKey of ActionLookupKey: %s", actionLookupKey);
  }

  @Override
  public boolean valueIsShareable() {
    // BuildDriverValue is just a wrapper value that signals that the building of a top level target
    // was concluded. It's meant to be created anew each build, since BuildDriverFunction must be
    // run every build.
    return false;
  }

  enum TestType {
    NOT_TEST("not-test"),
    PARALLEL("parallel"),
    EXCLUSIVE("exclusive"),
    EXCLUSIVE_IF_LOCAL("exclusive-if-local");

    private final String msg;

    TestType(String msg) {
      this.msg = msg;
    }

    public String getMsg() {
      return msg;
    }
  }
}
