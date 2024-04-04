// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.Charset.UTF8;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.analysis.NoBuildEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModTidyValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionEvent;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.CompiledModuleFile;
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFile;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.RootModuleFileFixupEvent;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ExtensionArg;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ExtensionArg.ExtensionArgConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.InvalidArgumentException;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ModSubcommand;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ModSubcommandConverter;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModuleArg.ModuleArgConverter;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code;
import com.google.devtools.build.lib.shell.AbnormalTerminationException;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.CommandBuilder;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.util.MaybeCompleteSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Queries the Bzlmod external dependency graph. */
@Command(
    name = ModCommand.NAME,
    // TODO(andreisolo): figure out which extra options are really needed
    options = {
      ModOptions.class,
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    help = "resource:mod.txt",
    shortDescription = "Queries the Bzlmod external dependency graph",
    allowResidue = true)
public final class ModCommand implements BlazeCommand {

  public static final String NAME = "mod";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    if (!options.getOptions(BuildLanguageOptions.class).enableBzlmod) {
      return reportAndCreateFailureResult(
          env,
          "Bzlmod has to be enabled for mod command to work, run with --enable_bzlmod",
          Code.MISSING_ARGUMENTS);
    }

    env.getEventBus()
        .post(
            new NoBuildEvent(
                env.getCommandName(),
                env.getCommandStartTime(),
                /* separateFinishedEvent= */ true,
                /* showProgress= */ true,
                /* id= */ null));
    BlazeCommandResult result = execInternal(env, options);
    env.getEventBus()
        .post(
            new NoBuildRequestFinishedEvent(
                result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()));
    return result;
  }

  private BlazeCommandResult execInternal(CommandEnvironment env, OptionsParsingResult options) {
    ModOptions modOptions = options.getOptions(ModOptions.class);
    Preconditions.checkArgument(modOptions != null);

    if (options.getResidue().isEmpty()) {
      String errorMessage =
          String.format(
              "No subcommand specified, choose one of : %s.", ModSubcommand.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN);
    }

    // The first element in the residue must be the subcommand, and then comes a list of arguments.
    String subcommandStr = options.getResidue().get(0);
    ModSubcommand subcommand;
    try {
      subcommand = new ModSubcommandConverter().convert(subcommandStr);
    } catch (OptionsParsingException e) {
      String errorMessage =
          String.format("Invalid subcommand, choose one from : %s.", ModSubcommand.printValues());
      return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN);
    }
    List<String> args = options.getResidue().subList(1, options.getResidue().size());

    ImmutableList.Builder<RepositoryMappingValue.Key> repositoryMappingKeysBuilder =
        ImmutableList.builder();
    if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
      if (args.isEmpty()) {
        // Make this case an error so that we are free to add a mode that emits all mappings in a
        // single JSON object later.
        return reportAndCreateFailureResult(
            env, "No repository name(s) specified", Code.INVALID_ARGUMENTS);
      }
      for (String arg : args) {
        try {
          repositoryMappingKeysBuilder.add(RepositoryMappingValue.key(RepositoryName.create(arg)));
        } catch (LabelSyntaxException e) {
          return reportAndCreateFailureResult(env, e.getMessage(), Code.INVALID_ARGUMENTS);
        }
      }
    }
    ImmutableList<RepositoryMappingValue.Key> repoMappingKeys =
        repositoryMappingKeysBuilder.build();

    BazelDepGraphValue depGraphValue;
    @Nullable BazelModuleInspectorValue moduleInspector;
    @Nullable BazelModTidyValue modTidyValue;
    ImmutableList<RepositoryMappingValue> repoMappingValues;
    TidyEventRecorder tidyEventRecorder = new TidyEventRecorder();

    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);

    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();

    try {
      env.syncPackageLoading(options);

      ImmutableSet.Builder<SkyKey> keys = ImmutableSet.builder();
      if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
        keys.addAll(repoMappingKeys);
      } else if (subcommand.equals(ModSubcommand.TIDY)) {
        keys.add(BazelModTidyValue.KEY);
        env.getEventBus().register(tidyEventRecorder);
      } else {
        keys.add(BazelDepGraphValue.KEY, BazelModuleInspectorValue.KEY);
      }
      EvaluationResult<SkyValue> evaluationResult =
          skyframeExecutor.prepareAndGet(keys.build(), evaluationContext);

      if (evaluationResult.hasError()) {
        Exception e = evaluationResult.getError().getException();
        String message = "Unexpected error during repository rule evaluation.";
        if (e != null) {
          message = e.getMessage();
        }
        return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
      }

      depGraphValue = (BazelDepGraphValue) evaluationResult.get(BazelDepGraphValue.KEY);

      moduleInspector =
          (BazelModuleInspectorValue) evaluationResult.get(BazelModuleInspectorValue.KEY);

      modTidyValue = (BazelModTidyValue) evaluationResult.get(BazelModTidyValue.KEY);

      repoMappingValues =
          repoMappingKeys.stream()
              .map(evaluationResult::get)
              .map(RepositoryMappingValue.class::cast)
              .collect(toImmutableList());
    } catch (InterruptedException e) {
      String errorMessage = "mod command interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    } catch (AbruptExitException e) {
      env.getReporter().handle(Event.error(null, "Unknown error: " + e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    }

    // Handle commands that do not require BazelModuleInspectorValue.
    if (subcommand.equals(ModSubcommand.DUMP_REPO_MAPPING)) {
      String missingRepos =
          IntStream.range(0, repoMappingKeys.size())
              .filter(i -> repoMappingValues.get(i) == RepositoryMappingValue.NOT_FOUND_VALUE)
              .mapToObj(repoMappingKeys::get)
              .map(RepositoryMappingValue.Key::repoName)
              .map(RepositoryName::getName)
              .collect(joining(", "));
      if (!missingRepos.isEmpty()) {
        return reportAndCreateFailureResult(
            env, "Repositories not found: " + missingRepos, Code.INVALID_ARGUMENTS);
      }
      try {
        dumpRepoMappings(
            repoMappingValues,
            new OutputStreamWriter(
                env.getReporter().getOutErr().getOutputStream(),
                modOptions.charset == UTF8 ? UTF_8 : US_ASCII));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return BlazeCommandResult.success();
    } else if (subcommand == ModSubcommand.TIDY) {
      // tidy doesn't take extra arguments.
      if (!args.isEmpty()) {
        return reportAndCreateFailureResult(
            env, "the 'tidy' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS);
      }
      return runTidy(env, modTidyValue, tidyEventRecorder);
    }

    // Extract and check the --base_module argument first to use it when parsing the other args.
    // Can only be a TargetModule or a repoName relative to the ROOT.
    ModuleKey baseModuleKey;
    AugmentedModule rootModule = moduleInspector.getDepGraph().get(ModuleKey.ROOT);
    try {
      ImmutableSet<ModuleKey> keys =
          modOptions.baseModule.resolveToModuleKeys(
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              moduleInspector.getModuleKeyToCanonicalNames(),
              rootModule.getDeps(),
              rootModule.getUnusedDeps(),
              false,
              false);
      if (keys.size() > 1) {
        throw new InvalidArgumentException(
            String.format(
                "The --base_module option can only specify exactly one module version, choose one"
                    + " of: %s.",
                keys.stream().map(ModuleKey::toString).collect(joining(", "))),
            Code.INVALID_ARGUMENTS);
      }
      baseModuleKey = Iterables.getOnlyElement(keys);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --base_module %s option: %s (Note that unused modules cannot be used here)",
              modOptions.baseModule, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    // The args can have different types depending on the subcommand, so create multiple containers
    // which can be filled accordingly.
    ImmutableSet<ModuleKey> argsAsModules = null;
    ImmutableSortedSet<ModuleExtensionId> argsAsExtensions = null;
    ImmutableMap<String, RepositoryName> argsAsRepos = null;

    AugmentedModule baseModule =
        Objects.requireNonNull(moduleInspector.getDepGraph().get(baseModuleKey));
    RepositoryMapping baseModuleMapping = depGraphValue.getFullRepoMapping(baseModuleKey);
    try {
      switch (subcommand) {
        case GRAPH:
          // GRAPH doesn't take extra arguments.
          if (!args.isEmpty()) {
            throw new InvalidArgumentException(
                "the 'graph' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS);
          }
          break;
        case SHOW_REPO:
          ImmutableMap.Builder<String, RepositoryName> targetToRepoName =
              new ImmutableMap.Builder<>();
          for (String arg : args) {
            try {
              targetToRepoName.putAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToRepoNames(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          moduleInspector.getModuleKeyToCanonicalNames(),
                          baseModuleMapping));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format(
                      "In repo argument %s: %s (Note that unused modules cannot be used here)",
                      arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsRepos = targetToRepoName.buildKeepingLast();
          break;
        case SHOW_EXTENSION:
          ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
              new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
          for (String arg : args) {
            try {
              extensionsBuilder.add(
                  ExtensionArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToExtensionId(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          moduleInspector.getModuleKeyToCanonicalNames(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps()));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In extension argument %s: %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS,
                  e);
            }
          }
          argsAsExtensions = extensionsBuilder.build();
          break;
        default:
          ImmutableSet.Builder<ModuleKey> keysBuilder = new ImmutableSet.Builder<>();
          for (String arg : args) {
            try {
              keysBuilder.addAll(
                  ModuleArgConverter.INSTANCE
                      .convert(arg)
                      .resolveToModuleKeys(
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          moduleInspector.getModuleKeyToCanonicalNames(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps(),
                          modOptions.includeUnused,
                          /* warnUnused= */ true));
            } catch (InvalidArgumentException | OptionsParsingException e) {
              throw new InvalidArgumentException(
                  String.format("In module argument %s: %s", arg, e.getMessage()),
                  Code.INVALID_ARGUMENTS);
            }
          }
          argsAsModules = keysBuilder.build();
      }
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(env, e.getMessage(), e.getCode());
    }
    /* Extract and check the --from and --extension_usages argument */
    ImmutableSet<ModuleKey> fromKeys;
    ImmutableSet<ModuleKey> usageKeys;
    try {
      fromKeys =
          moduleArgListToKeys(
              modOptions.modulesFrom,
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              moduleInspector.getModuleKeyToCanonicalNames(),
              baseModule.getDeps(),
              baseModule.getUnusedDeps(),
              modOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format("In --from %s option: %s", modOptions.modulesFrom, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    try {
      usageKeys =
          moduleArgListToKeys(
              modOptions.extensionUsages,
              moduleInspector.getModulesIndex(),
              moduleInspector.getDepGraph(),
              moduleInspector.getModuleKeyToCanonicalNames(),
              baseModule.getDeps(),
              baseModule.getUnusedDeps(),
              modOptions.includeUnused);
    } catch (InvalidArgumentException e) {
      return reportAndCreateFailureResult(
          env,
          String.format(
              "In --extension_usages %s option: %s (Note that unused modules cannot be used"
                  + " here)",
              modOptions.extensionUsages, e.getMessage()),
          Code.INVALID_ARGUMENTS);
    }

    /* Extract and check the --extension_filter argument */
    Optional<MaybeCompleteSet<ModuleExtensionId>> filterExtensions = Optional.empty();
    if (subcommand.isGraph() && modOptions.extensionFilter != null) {
      if (modOptions.extensionFilter.isEmpty()) {
        filterExtensions = Optional.of(MaybeCompleteSet.completeSet());
      } else {
        try {
          filterExtensions =
              Optional.of(
                  MaybeCompleteSet.copyOf(
                      extensionArgListToIds(
                          modOptions.extensionFilter,
                          moduleInspector.getModulesIndex(),
                          moduleInspector.getDepGraph(),
                          moduleInspector.getModuleKeyToCanonicalNames(),
                          baseModule.getDeps(),
                          baseModule.getUnusedDeps())));
        } catch (InvalidArgumentException e) {
          return reportAndCreateFailureResult(
              env,
              String.format(
                  "In --extension_filter %s option: %s",
                  modOptions.extensionFilter, e.getMessage()),
              Code.INVALID_ARGUMENTS);
        }
      }
    }

    ImmutableMap<String, BzlmodRepoRuleValue> targetRepoRuleValues = null;
    try {
      // If subcommand is a SHOW, also request the BzlmodRepoRuleValues from Skyframe.
      if (subcommand == ModSubcommand.SHOW_REPO) {
        ImmutableSet<SkyKey> skyKeys =
            argsAsRepos.values().stream().map(BzlmodRepoRuleValue::key).collect(toImmutableSet());
        EvaluationResult<SkyValue> result =
            env.getSkyframeExecutor().prepareAndGet(skyKeys, evaluationContext);
        if (result.hasError()) {
          Exception e = result.getError().getException();
          String message = "Unexpected error during repository rule evaluation.";
          if (e != null) {
            message = e.getMessage();
          }
          return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS);
        }
        targetRepoRuleValues =
            argsAsRepos.entrySet().stream()
                .collect(
                    toImmutableMap(
                        Entry::getKey,
                        e ->
                            (BzlmodRepoRuleValue)
                                result.get(BzlmodRepoRuleValue.key(e.getValue()))));
        for (Map.Entry<String, BzlmodRepoRuleValue> entry : targetRepoRuleValues.entrySet()) {
          if (entry.getValue() == BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE) {
            return reportAndCreateFailureResult(
                env,
                String.format("In repo argument %s: no such repo", entry.getKey()),
                Code.INVALID_ARGUMENTS);
          }
        }
      }
    } catch (InterruptedException e) {
      String errorMessage = "mod command interrupted: " + e.getMessage();
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    // Workaround to allow different default value for DEPS and EXPLAIN, and also use
    // Integer.MAX_VALUE instead of the exact number string.
    if (modOptions.depth < 1) {
      switch (subcommand) {
        case EXPLAIN:
          modOptions.depth = 1;
          break;
        case DEPS:
          modOptions.depth = 2;
          break;
        default:
          modOptions.depth = Integer.MAX_VALUE;
      }
    }

    ModExecutor modExecutor =
        new ModExecutor(
            moduleInspector.getDepGraph(),
            depGraphValue.getExtensionUsagesTable(),
            moduleInspector.getExtensionToRepoInternalNames(),
            filterExtensions,
            modOptions,
            new OutputStreamWriter(
                env.getReporter().getOutErr().getOutputStream(),
                modOptions.charset == UTF8 ? UTF_8 : US_ASCII));

    switch (subcommand) {
      case GRAPH:
        modExecutor.graph(fromKeys);
        break;
      case DEPS:
        modExecutor.graph(argsAsModules);
        break;
      case PATH:
        modExecutor.path(fromKeys, argsAsModules);
        break;
      case ALL_PATHS:
      case EXPLAIN:
        modExecutor.allPaths(fromKeys, argsAsModules);
        break;
      case SHOW_REPO:
        modExecutor.showRepo(targetRepoRuleValues);
        break;
      case SHOW_EXTENSION:
        modExecutor.showExtension(argsAsExtensions, usageKeys);
        break;
      default:
        throw new IllegalStateException("Unexpected subcommand: " + subcommand);
    }

    return BlazeCommandResult.success();
  }

  private static class TidyEventRecorder {
    final List<RootModuleFileFixupEvent> fixupEvents = new ArrayList<>();
    @Nullable BazelModuleResolutionEvent bazelModuleResolutionEvent;

    @Subscribe
    public void fixupGenerated(RootModuleFileFixupEvent event) {
      fixupEvents.add(event);
    }

    @Subscribe
    public void bazelModuleResolved(BazelModuleResolutionEvent event) {
      bazelModuleResolutionEvent = event;
    }
  }

  private BlazeCommandResult runTidy(
      CommandEnvironment env, BazelModTidyValue modTidyValue, TidyEventRecorder eventRecorder) {
    CommandBuilder buildozerCommand =
        new CommandBuilder()
            .setWorkingDir(env.getWorkspace())
            .addArg(modTidyValue.buildozer().getPathString())
            .addArgs(
                Stream.concat(
                        eventRecorder.fixupEvents.stream()
                            .map(RootModuleFileFixupEvent::getBuildozerCommands)
                            .flatMap(Collection::stream),
                        Stream.of("format"))
                    .collect(toImmutableList()))
            .addArg("MODULE.bazel:all");
    try {
      buildozerCommand.build().execute();
    } catch (InterruptedException | CommandException e) {
      String suffix = "";
      if (e instanceof AbnormalTerminationException) {
        if (((AbnormalTerminationException) e).getResult().getTerminationStatus().getRawExitCode()
            == 3) {
          // Buildozer exits with exit code 3 if it didn't make any changes.
          return BlazeCommandResult.success();
        }
        suffix =
            ":\n" + new String(((AbnormalTerminationException) e).getResult().getStderr(), UTF_8);
      }
      return reportAndCreateFailureResult(
          env,
          "Unexpected error while running buildozer: " + e.getMessage() + suffix,
          Code.BUILDOZER_FAILED);
    }

    for (RootModuleFileFixupEvent fixupEvent : eventRecorder.fixupEvents) {
      env.getReporter().handle(Event.info(fixupEvent.getSuccessMessage()));
    }

    if (modTidyValue.lockfileMode().equals(LockfileMode.UPDATE)) {
      // We cannot safely rerun Skyframe evaluation here to pick up the updated module file.
      // Instead, we construct a new BazelModuleResolutionEvent with the updated module file
      // contents to be picked up by BazelLockFileModule. Since changing use_repos doesn't affect
      // module resolution or module extension evaluation, we can reuse the existing lockfile
      // information except for the root module file value.
      RootedPath moduleFilePath = ModuleFileFunction.getModuleFilePath(env.getWorkspace());
      byte[] moduleFileContents;
      try {
        moduleFileContents = FileSystemUtils.readContent(moduleFilePath.asPath());
      } catch (IOException e) {
        return reportAndCreateFailureResult(
            env,
            "Unexpected error while reading module file after running buildozer: " + e.getMessage(),
            Code.BUILDOZER_FAILED);
      }
      CompiledModuleFile compiledModuleFile;
      try {
        compiledModuleFile =
            CompiledModuleFile.parseAndCompile(
                ModuleFile.create(moduleFileContents, moduleFilePath.asPath().getPathString()),
                ModuleKey.ROOT,
                modTidyValue.starlarkSemantics(),
                env.getRuntime().getRuleClassProvider().getBazelStarlarkEnvironment(),
                env.getReporter());
      } catch (ExternalDepsException e) {
        return reportAndCreateFailureResult(
            env,
            "Unexpected error while compiling module file after running buildozer: "
                + e.getMessage(),
            Code.BUILDOZER_FAILED);
      }
      ModuleFileValue.RootModuleFileValue newRootModuleFileValue;
      try {
        newRootModuleFileValue =
            ModuleFileFunction.evaluateRootModuleFile(
                compiledModuleFile,
                modTidyValue.includeLabelToCompiledModuleFile(),
                ModuleFileFunction.getBuiltinModules(
                    env.getDirectories().getEmbeddedBinariesRoot()),
                modTidyValue.moduleOverrides(),
                modTidyValue.ignoreDevDeps(),
                modTidyValue.starlarkSemantics(),
                env.getReporter());
      } catch (SkyFunctionException | InterruptedException e) {
        return reportAndCreateFailureResult(
            env,
            "Unexpected error parsing module file after running buildozer: " + e.getMessage(),
            Code.BUILDOZER_FAILED);
      }
      // BazelModuleResolutionEvent is cached by Skyframe and thus always emitted.
      BazelModuleResolutionEvent updatedModuleResolutionEvent =
          BazelModuleResolutionEvent.create(
              eventRecorder.bazelModuleResolutionEvent.getOnDiskLockfileValue(),
              eventRecorder
                  .bazelModuleResolutionEvent
                  .getResolutionOnlyLockfileValue()
                  .withShallowlyReplacedRootModule(newRootModuleFileValue),
              eventRecorder.bazelModuleResolutionEvent.getExtensionUsagesById());
      env.getReporter().post(updatedModuleResolutionEvent);
    }

    return BlazeCommandResult.success();
  }

  /** Collects a list of {@link ModuleArg} into a set of {@link ModuleKey}s. */
  private static ImmutableSet<ModuleKey> moduleArgListToKeys(
      ImmutableList<ModuleArg> argList,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps,
      boolean includeUnused)
      throws InvalidArgumentException {
    ImmutableSet.Builder<ModuleKey> allTargetKeys = new ImmutableSet.Builder<>();
    for (ModuleArg moduleArg : argList) {
      allTargetKeys.addAll(
          moduleArg.resolveToModuleKeys(
              modulesIndex,
              depGraph,
              moduleKeyToCanonicalNames,
              baseModuleDeps,
              baseModuleUnusedDeps,
              includeUnused,
              true));
    }
    return allTargetKeys.build();
  }

  private static ImmutableSortedSet<ModuleExtensionId> extensionArgListToIds(
      ImmutableList<ExtensionArg> args,
      ImmutableMap<String, ImmutableSet<ModuleKey>> modulesIndex,
      ImmutableMap<ModuleKey, AugmentedModule> depGraph,
      ImmutableMap<ModuleKey, RepositoryName> moduleKeyToCanonicalNames,
      ImmutableBiMap<String, ModuleKey> baseModuleDeps,
      ImmutableBiMap<String, ModuleKey> baseModuleUnusedDeps)
      throws InvalidArgumentException {
    ImmutableSortedSet.Builder<ModuleExtensionId> extensionsBuilder =
        new ImmutableSortedSet.Builder<>(ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
    for (ExtensionArg arg : args) {
      extensionsBuilder.add(
          arg.resolveToExtensionId(
              modulesIndex,
              depGraph,
              moduleKeyToCanonicalNames,
              baseModuleDeps,
              baseModuleUnusedDeps));
    }
    return extensionsBuilder.build();
  }

  private static BlazeCommandResult reportAndCreateFailureResult(
      CommandEnvironment env, String message, Code detailedCode) {
    String fullMessage =
        String.format(
            "%s%s Type '%s help mod' for syntax and help.",
            message, message.endsWith(".") ? "" : ".", env.getRuntime().getProductName());
    env.getReporter().handle(Event.error(fullMessage));
    return createFailureResult(fullMessage, detailedCode);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.detailedExitCode(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setModCommand(FailureDetails.ModCommand.newBuilder().setCode(detailedCode).build())
                .setMessage(message)
                .build()));
  }

  public static void dumpRepoMappings(List<RepositoryMappingValue> repoMappings, Writer writer)
      throws IOException {
    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    for (RepositoryMappingValue repoMapping : repoMappings) {
      JsonWriter jsonWriter = gson.newJsonWriter(writer);
      jsonWriter.beginObject();
      for (Entry<String, RepositoryName> entry :
          repoMapping.getRepositoryMapping().entries().entrySet()) {
        jsonWriter.name(entry.getKey());
        jsonWriter.value(entry.getValue().getName());
      }
      jsonWriter.endObject();
      writer.write('\n');
    }
    writer.flush();
  }
}
