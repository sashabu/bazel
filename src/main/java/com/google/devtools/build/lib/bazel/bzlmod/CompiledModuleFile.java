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
//
package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.DotBazelFileSyntaxChecker;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;

/**
 * Represents a compiled MODULE.bazel file, ready to be executed on a {@link StarlarkThread}. It's
 * been successfully checked for syntax errors.
 *
 * <p>Use the {@link #parseAndCompile} factory method instead of directly instantiating this record.
 */
public record CompiledModuleFile(
    ModuleFile moduleFile,
    Program program,
    Module predeclaredEnv,
    ImmutableList<IncludeStatement> includeStatements) {
  public static final String INCLUDE_IDENTIFIER = "include";

  record IncludeStatement(String includeLabel, Location location) {}

  /** Parses and compiles a given module file, checking it for syntax errors. */
  public static CompiledModuleFile parseAndCompile(
      ModuleFile moduleFile,
      ModuleKey moduleKey,
      StarlarkSemantics starlarkSemantics,
      BazelStarlarkEnvironment starlarkEnv,
      ExtendedEventHandler eventHandler)
      throws ExternalDepsException {
    StarlarkFile starlarkFile =
        StarlarkFile.parse(ParserInput.fromUTF8(moduleFile.getContent(), moduleFile.getLocation()));
    if (!starlarkFile.ok()) {
      Event.replayEventsOn(eventHandler, starlarkFile.errors());
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE, "error parsing MODULE.bazel file for %s", moduleKey);
    }
    try {
      ImmutableList<IncludeStatement> includeStatements = checkModuleFileSyntax(starlarkFile);
      Module predeclaredEnv =
          Module.withPredeclared(
              starlarkSemantics, starlarkEnv.getStarlarkGlobals().getModuleToplevels());
      Program program = Program.compileFile(starlarkFile, predeclaredEnv);
      return new CompiledModuleFile(moduleFile, program, predeclaredEnv, includeStatements);
    } catch (SyntaxError.Exception e) {
      Event.replayEventsOn(eventHandler, e.errors());
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE, "syntax error in MODULE.bazel file for %s", moduleKey);
    }
  }

  @VisibleForTesting
  static ImmutableList<IncludeStatement> checkModuleFileSyntax(StarlarkFile starlarkFile)
      throws SyntaxError.Exception {
    var includeStatements = ImmutableList.<IncludeStatement>builder();
    new DotBazelFileSyntaxChecker("MODULE.bazel files", /* canLoadBzl= */ false) {
      @Override
      public void visit(ExpressionStatement node) {
        // We can assume this statement isn't nested in any block, since we don't allow
        // `if`/`def`/`for` in MODULE.bazel.
        if (node.getExpression() instanceof CallExpression call
            && call.getFunction() instanceof Identifier id
            && id.getName().equals(INCLUDE_IDENTIFIER)) {
          // Found a top-level call to `include`!
          if (call.getArguments().size() == 1
              && call.getArguments().getFirst() instanceof Argument.Positional pos
              && pos.getValue() instanceof StringLiteral str) {
            includeStatements.add(new IncludeStatement(str.getValue(), call.getStartLocation()));
            // We can stop going down this rabbit hole now.
            return;
          }
          error(
              node.getStartLocation(),
              "the `module_import` directive MUST be called with exactly one positional "
                  + "argument that is a string literal");
          return;
        }
        super.visit(node);
      }

      @Override
      public void visit(DotExpression node) {
        visit(node.getObject());
        if (!node.getField().getName().equals(INCLUDE_IDENTIFIER)) {
          // This is fine: `whatever.include`
          // (so `include` can be used as a tag class name)
          visit(node.getField());
        }
      }

      @Override
      public void visit(Identifier node) {
        if (node.getName().equals(INCLUDE_IDENTIFIER)) {
          // If we somehow reach the `include` identifier but NOT as the other allowed cases above,
          // cry foul.
          error(
              node.getStartLocation(),
              "the `include` directive MUST be called directly at the top-level");
        }
        super.visit(node);
      }
    }.check(starlarkFile);
    return includeStatements.build();
  }

  public void runOnThread(StarlarkThread thread) throws EvalException, InterruptedException {
    Starlark.execFileProgram(program, predeclaredEnv, thread);
  }
}
