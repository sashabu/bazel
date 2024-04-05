# pylint: disable=g-backslash-continuation
# Copyright 2024 The Bazel Authors. All rights reserved.
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
# pylint: disable=g-long-ternary

import os
import tempfile
from absl.testing import absltest
from src.test.py.bazel import test_base
from src.test.py.bazel.bzlmod.test_utils import BazelRegistry


class BazelVendorTest(test_base.TestBase):

  def setUp(self):
    test_base.TestBase.setUp(self)
    self.registries_work_dir = tempfile.mkdtemp(dir=self._test_cwd)
    self.main_registry = BazelRegistry(
        os.path.join(self.registries_work_dir, 'main')
    )
    self.ScratchFile(
        '.bazelrc',
        [
            # In ipv6 only network, this has to be enabled.
            # 'startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true',
            'common --noenable_workspace',
            'common --experimental_isolated_extension_usages',
            'common --registry=' + self.main_registry.getURL(),
            'common --registry=https://bcr.bazel.build',
            'common --verbose_failures',
            # Set an explicit Java language version
            'common --java_language_version=8',
            'common --tool_java_language_version=8',
            'common --lockfile_mode=update',
            'startup --windows_enable_symlinks' if self.IsWindows() else '',
        ],
    )
    self.ScratchFile('MODULE.bazel')
    self.generateBuiltinModules()

  def generateBuiltinModules(self):
    self.ScratchFile('platforms_mock/BUILD')
    self.ScratchFile(
        'platforms_mock/MODULE.bazel', ['module(name="local_config_platform")']
    )

    self.ScratchFile('tools_mock/BUILD')
    self.ScratchFile('tools_mock/MODULE.bazel', ['module(name="bazel_tools")'])
    self.ScratchFile('tools_mock/tools/build_defs/repo/BUILD')
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/cache.bzl'),
        'tools_mock/tools/build_defs/repo/cache.bzl',
    )
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/http.bzl'),
        'tools_mock/tools/build_defs/repo/http.bzl',
    )
    self.CopyFile(
        self.Rlocation('io_bazel/tools/build_defs/repo/utils.bzl'),
        'tools_mock/tools/build_defs/repo/utils.bzl',
    )

  def testBasicVendoring(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '--vendor_dir=vendor'])

    # Assert repos are vendored with marker files and .vendorignore is created
    repos_vendored = os.listdir(self._test_cwd + '/vendor')
    self.assertIn('aaa~', repos_vendored)
    self.assertIn('bbb~', repos_vendored)
    self.assertIn('@aaa~.marker', repos_vendored)
    self.assertIn('@bbb~.marker', repos_vendored)
    self.assertIn('.vendorignore', repos_vendored)

  def testVendoringMultipleTimes(self):
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    # Clean the external cache
    self.RunBazel(['clean', '--expunge'])
    # Re-vendoring should NOT re-fetch, but only create symlinks
    # We need to check this because the vendor logic depends on the fetch logic,
    # but we don't want to re-fetch if our vendored repo is already up-to-date!
    self.RunBazel(['vendor', '--vendor_dir=vendor'])

    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repo_path = stdout[0] + '/external/aaa~'
    if self.IsWindows():
      self.assertTrue(self.IsJunction(repo_path))
    else:
      self.assertTrue(os.path.islink(repo_path))

  def testVendorRepo(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    ).createCcModule('ccc', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'bazel_dep(name = "ccc", version = "1.0", repo_name = "my_repo")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    # Test canonical/apparent repo names & multiple repos
    self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--repo=@@bbb~', '--repo=@my_repo']
    )
    repos_vendored = os.listdir(self._test_cwd + '/vendor')
    self.assertIn('bbb~', repos_vendored)
    self.assertIn('ccc~', repos_vendored)
    self.assertNotIn('aaa~', repos_vendored)

  def testVendorExistingRepo(self):
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0", repo_name = "my_repo")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    # Test canonical/apparent repo names & multiple repos
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@my_repo'])
    self.assertIn('aaa~', os.listdir(self._test_cwd + '/vendor'))

    # Delete repo from external cache
    self.RunBazel(['clean', '--expunge'])
    # Vendoring again should find that it is already up-to-date and exclude it
    # from vendoring not fail
    self.RunBazel(['vendor', '--vendor_dir=vendor', '--repo=@my_repo'])

  def testVendorInvalidRepo(self):
    # Invalid repo name (not canonical or apparent)
    exit_code, _, stderr = self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--repo=hello'], allow_failure=True
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        'ERROR: Invalid repo name: The repo value has to be either apparent'
        " '@repo' or canonical '@@repo' repo name",
        stderr,
    )
    # Repo does not exist
    self.ScratchFile(
        'MODULE.bazel',
        [
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    exit_code, _, stderr = self.RunBazel(
        ['vendor', '--vendor_dir=vendor', '--repo=@@nono', '--repo=@nana'],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 8, stderr)
    self.assertIn(
        "ERROR: Vendoring some repos failed with errors: [Repository '@@nono'"
        " is not defined, No repository visible as '@nana' from main"
        ' repository]',
        stderr,
    )

  # Remove this test when workspace is removed
  def testVendorDirIsNotCheckedForWorkspaceRepos(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile(
        'WORKSPACE.bzlmod',
        ['load("//:main.bzl", "dump_env")', 'dump_env(name = "dummyRepo")'],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'main.bzl',
        [
            'def _dump_env(ctx):',
            '    ctx.file("BUILD")',
            'dump_env = repository_rule(implementation = _dump_env)',
        ],
    )
    _, _, stderr = self.RunBazel([
        'fetch',
        '@@dummyRepo//:all',
        '--enable_workspace=true',
        '--vendor_dir=blabla',
    ])
    self.assertNotIn(
        "Vendored repository 'dummyRepo' is out-of-date.", '\n'.join(stderr)
    )

  def testBuildingWithVendoredRepos(self):
    self.main_registry.createCcModule('aaa', '1.0')
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "aaa", version = "1.0")',
        ],
    )
    self.ScratchFile('BUILD')
    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    self.assertIn('aaa~', os.listdir(self._test_cwd + '/vendor'))

    # Empty external & build with vendor
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(['build', '@aaa//:all', '--vendor_dir=vendor'])
    self.assertNotIn(
        "Vendored repository '_main~ext~justRepo' is out-of-date.",
        '\n'.join(stderr),
    )

    # Assert repo aaa in {OUTPUT_BASE}/external is a symlink (junction on
    # windows, this validates it was created from vendor and not fetched)=
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repo_path = stdout[0] + '/external/aaa~'
    if self.IsWindows():
      self.assertTrue(self.IsJunction(repo_path))
    else:
      self.assertTrue(os.path.islink(repo_path))

  def testIgnoreFromVendoring(self):
    # Repos should be excluded from vendoring:
    # 1.Local Repos, 2.Config Repos, 3.Repos declared in .vendorignore file
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
        'bbb', '1.0', {'aaa': '1.0'}
    )
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "bbb", version = "1.0")',
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "regularRepo")',
            'use_repo(ext, "localRepo")',
            'use_repo(ext, "configRepo")',
            'local_path_override(module_name="bazel_tools", path="tools_mock")',
            'local_path_override(module_name="local_config_platform", ',
            'path="platforms_mock")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD")',
            '',
            'repo_rule1 = repository_rule(implementation=_repo_rule_impl)',
            'repo_rule2 = repository_rule(implementation=_repo_rule_impl, ',
            'local=True)',
            'repo_rule3 = repository_rule(implementation=_repo_rule_impl, ',
            'configure=True)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule1(name="regularRepo")',
            '    repo_rule2(name="localRepo")',
            '    repo_rule3(name="configRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    os.makedirs(self._test_cwd + '/vendor', exist_ok=True)
    with open(self._test_cwd + '/vendor/.vendorignore', 'w') as f:
      f.write('aaa~\n')

    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    repos_vendored = os.listdir(self._test_cwd + '/vendor')

    # Assert bbb and the regularRepo are vendored with marker files
    self.assertIn('bbb~', repos_vendored)
    self.assertIn('@bbb~.marker', repos_vendored)
    self.assertIn('_main~ext~regularRepo', repos_vendored)
    self.assertIn('@_main~ext~regularRepo.marker', repos_vendored)

    # Assert aaa (from .vendorignore), local and config repos are not vendored
    self.assertNotIn('aaa~', repos_vendored)
    self.assertNotIn('bazel_tools', repos_vendored)
    self.assertNotIn('local_config_platform', repos_vendored)
    self.assertNotIn('_main~ext~localRepo', repos_vendored)
    self.assertNotIn('_main~ext~configRepo', repos_vendored)

  def testBuildingOutOfDateVendoredRepo(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "justRepo")',
        ],
    )
    self.ScratchFile('BUILD')
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="justRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Vendor, assert and build with no problems
    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    self.assertIn('_main~ext~justRepo', os.listdir(self._test_cwd + '/vendor'))
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    self.assertNotIn(
        "WARNING: <builtin>: Vendored repository '_main~ext~justRepo' is"
        ' out-of-date. The up-to-date version will be fetched into the external'
        ' cache and used. To update the repo in the  vendor directory, run'
        " 'bazel vendor'",
        stderr,
    )

    # Make updates in repo definition
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'haha\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="justRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Clean cache, and re-build with vendor
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    # Assert repo in vendor is out-of-date, and the new one is fetched into
    # external and not a symlink
    self.assertIn(
        "WARNING: <builtin>: Vendored repository '_main~ext~justRepo' is"
        ' out-of-date. The up-to-date version will be fetched into the external'
        ' cache and used. To update the repo in the  vendor directory, run'
        " 'bazel vendor'",
        stderr,
    )
    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    self.assertFalse(os.path.islink(stdout[0] + '/external/bbb~'))

    # Assert vendoring again solves the problem
    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    self.RunBazel(['clean', '--expunge'])
    _, _, stderr = self.RunBazel(
        ['build', '@justRepo//:all', '--vendor_dir=vendor']
    )
    self.assertNotIn(
        "WARNING: <builtin>: Vendored repository '_main~ext~justRepo' is"
        ' out-of-date. The up-to-date version will be fetched into the external'
        ' cache and used. To update the repo in the  vendor directory, run'
        " 'bazel vendor'",
        stderr,
    )

  def testBuildingVendoredRepoInOfflineMode(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "venRepo")',
        ],
    )
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'lala\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )
    self.ScratchFile('BUILD')

    # Vendor, assert and build with no problems
    self.RunBazel(['vendor', '--vendor_dir=vendor'])
    self.assertIn('_main~ext~venRepo', os.listdir(self._test_cwd + '/vendor'))

    # Make updates in repo definition
    self.ScratchFile(
        'MODULE.bazel',
        [
            'ext = use_extension("extension.bzl", "ext")',
            'use_repo(ext, "venRepo")',
            'use_repo(ext, "noVenRepo")',
        ],
    )
    self.ScratchFile(
        'extension.bzl',
        [
            'def _repo_rule_impl(ctx):',
            '    ctx.file("WORKSPACE")',
            '    ctx.file("BUILD", "filegroup(name=\'haha\')")',
            'repo_rule = repository_rule(implementation=_repo_rule_impl)',
            '',
            'def _ext_impl(ctx):',
            '    repo_rule(name="venRepo")',
            '    repo_rule(name="noVenRepo")',
            'ext = module_extension(implementation=_ext_impl)',
        ],
    )

    # Building a repo that is not vendored in offline mode, should fail
    _, _, stderr = self.RunBazel(
        ['build', '@noVenRepo//:all', '--vendor_dir=vendor', '--nofetch'],
        allow_failure=True,
    )
    self.assertIn(
        'ERROR: Vendored repository _main~ext~noVenRepo not found under the'
        " vendor directory and fetching is disabled. To fix run 'bazel"
        " vendor' or build without the '--nofetch'",
        stderr,
    )

    # Building out-of-date repo in offline mode, should build the out-dated one
    # and emit a warning
    _, _, stderr = self.RunBazel(
        ['build', '@venRepo//:all', '--vendor_dir=vendor', '--nofetch'],
    )
    self.assertIn(
        "WARNING: <builtin>: Vendored repository '_main~ext~venRepo' is"
        ' out-of-date and fetching is disabled. Run build without the'
        " '--nofetch' option or run `bazel vendor` to update it",
        stderr,
    )
    # Assert the out-dated repo is the one built with
    self.assertIn(
        'Target @@_main~ext~venRepo//:lala up-to-date (nothing to build)',
        stderr,
    )

  def testBasicVendorTarget(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
      'bbb', '1.0')
    self.ScratchFile(
      'MODULE.bazel',
      [
        'bazel_dep(name = "aaa", version = "1.0")',
        'bazel_dep(name = "bbb", version = "1.0")',
      ],
    )
    self.ScratchFile('BUILD')

    self.RunBazel(['vendor', '@aaa//:lib_aaa', '@bbb//:lib_bbb',
                   '--vendor_dir=vendor'])
    # Assert aaa & bbb and are vendored
    self.assertIn('aaa~', os.listdir(self._test_cwd + '/vendor'))
    self.assertIn('bbb~', os.listdir(self._test_cwd + '/vendor'))

  def testVendorTarget(self):
    self.main_registry.createCcModule('aaa', '1.0').createCcModule(
      'bbb', '1.0', {'aaa': '1.0'})
    self.ScratchFile(
      'MODULE.bazel',
      [
        'bazel_dep(name = "bbb", version = "1.0")',
      ],
    )
    self.ScratchFile(
      'BUILD',
      [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = [',
        '    "@bbb//:lib_bbb",',
        '  ],',
        ')',
      ],
    )
    self.ScratchFile(
      'main.cc',
      [
        '#include "aaa.h"',
        'int main() {',
        '    hello_aaa("Hello there!");',
        '}',
      ],
    )

    self.RunBazel(['vendor', '//:main', '--vendor_dir=vendor'])

    # Clean and Assert running the target doesn't cause any repo fetch, but
    # only symlinks, meaning it is using what is under /vendor directory
    self.RunBazel(['clean', '--expunge'])
    _, stdout, _ = self.RunBazel(['run', '//:main', '--vendor_dir=vendor'])
    self.assertIn('Hello there! => aaa@1.0', stdout)

    _, stdout, _ = self.RunBazel(['info', 'output_base'])
    repo_path = stdout[0] + '/external/aaa~'
    if self.IsWindows():
      self.assertTrue(self.IsJunction(repo_path))
    else:
      self.assertTrue(os.path.islink(repo_path))


if __name__ == '__main__':
  absltest.main()
