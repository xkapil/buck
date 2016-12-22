/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.file;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMock;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

import javax.annotation.Nullable;

public class RemoteFileTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void ensureOutputIsAddedToBuildableContextSoItIsCached() throws Exception {
    Downloader downloader = new ExplodingDownloader();
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");
    RemoteFile remoteFile =
        (RemoteFile) new RemoteFileBuilder(downloader, target)
            .setUrl("http://www.facebook.com/")
            .setSha1(Hashing.sha1().hashLong(42))
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));

    BuildableContext buildableContext = EasyMock.createNiceMock(BuildableContext.class);
    buildableContext.recordArtifact(remoteFile.getPathToOutput());
    EasyMock.replay(buildableContext);

    remoteFile.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, buildableContext);

    EasyMock.verify(buildableContext);
  }

  @Test
  public void shouldSaveToFinalLocationAfterSha1IsVerified() throws Exception {
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode);

    assertThat(output, exists());
  }

  @Test
  public void shouldNotSaveToFinalLocationUntilAfterSha1IsVerified() throws Exception {
    Path output = runTheMagic(null, "eat more cheese", Hashing.sha1().hashLong(42));

    assertThat(output, not(exists()));
  }

  @Test
  public void shouldNotSaveFileToFinalLocationIfTheDownloadFails() throws Exception {
    String value = "I also like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(new ExplodingDownloader(), value, hashCode);

    assertThat(output, not(exists()));
  }

  @Test
  public void shouldNotMakeDownloadedFileExecutableWhenTypeIsData() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode, RemoteFile.Type.DATA);

    assertThat(output, exists());
    assertThat(output, not(isExecutable()));
  }

  @Test
  public void shouldMakeDownloadedFileExecutableIfRequested() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode, RemoteFile.Type.EXECUTABLE);

    assertThat(output, exists());
    assertThat(output, isExecutable());
  }

  private static Matcher<Path> exists() {
    return new BaseMatcher<Path>() {
      @Override
      public boolean matches(Object o) {
        return Files.exists((Path) o);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("File must exist");
      }
    };
  }

  private static Matcher<Path> isExecutable() {
    return new BaseMatcher<Path>() {
      @Override
      public boolean matches(Object o) {
        try {
          Set<PosixFilePermission> permissions = Files.getPosixFilePermissions((Path) o);
          return permissions.containsAll(Arrays.asList(
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_EXECUTE));
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("File must be executable");
      }
    };
  }

  private Path runTheMagic(
      @Nullable Downloader downloader,
      String contentsOfFile,
      HashCode hashCode) throws Exception {
    return runTheMagic(downloader, contentsOfFile, hashCode, RemoteFile.Type.DATA);
  }

  private Path runTheMagic(
      @Nullable Downloader downloader,
      String contentsOfFile,
      HashCode hashCode,
      RemoteFile.Type type) throws Exception {
    final byte[] bytes = contentsOfFile.getBytes(UTF_8);

    if (downloader == null) {
      downloader = (eventBus, uri, output) -> {
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
        return true;
      };
    }

    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath().toAbsolutePath());

    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//cake:walk")
        .setProjectFilesystem(filesystem)
        .build();
    RemoteFile remoteFile = new RemoteFile(
        params,
        new SourcePathResolver(
            new BuildRuleResolver(
              TargetGraph.EMPTY,
              new DefaultTargetNodeToBuildRuleTransformer())
        ),
        downloader,
        new URI("http://example.com"),
        hashCode,
        "output.txt",
        type);

    ImmutableList<Step> buildSteps = remoteFile.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    ExecutionContext context = TestExecutionContext.newInstance();
    for (Step buildStep : buildSteps) {
      int result = buildStep.execute(context).getExitCode();
      if (result != 0) {
        break;
      }
    }

    return filesystem.resolve(remoteFile.getPathToOutput());
  }
}
