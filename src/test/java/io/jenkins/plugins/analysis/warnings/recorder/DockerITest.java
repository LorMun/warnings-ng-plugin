package io.jenkins.plugins.analysis.warnings.recorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

import org.apache.commons.io.output.TeeOutputStream;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.model.Descriptor.FormException;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.DumbSlave;
import hudson.tasks.Maven;
import hudson.tasks.Shell;
import hudson.util.StreamTaskListener;
import hudson.util.VersionNumber;

import jenkins.security.s2m.AdminWhitelistRule;

import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.testutil.IntegrationTestWithJenkinsPerTest;
import io.jenkins.plugins.analysis.warnings.Cmake;
import io.jenkins.plugins.analysis.warnings.Gcc3;
import io.jenkins.plugins.analysis.warnings.MavenConsole;

import static io.jenkins.plugins.analysis.core.testutil.IntegrationTestWithJenkinsPerSuite.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assume.*;

public class DockerITest extends IntegrationTestWithJenkinsPerTest {
    @Rule
    public DockerRule<JavaContainer> javaDockerRule = new DockerRule<>(JavaContainer.class);

    @BeforeClass
    public static void assumeThatWeAreRunningLinux() throws Exception {
        assumeTrue("This test is only for Unix", !Functions.isWindows());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assumeThat("`docker version` could be run", new Launcher.LocalLauncher(StreamTaskListener.fromStderr()).launch()
                .cmds("docker", "version", "--format", "{{.Client.Version}}")
                .stdout(new TeeOutputStream(baos, System.err))
                .stderr(System.err)
                .join(), is(0));

        assumeThat("Docker must be at least 1.13.0 for this test (uses --init)",
                new VersionNumber(baos.toString().trim()), greaterThanOrEqualTo(new VersionNumber("1.13.0")));
    }

    @Test
    public void shouldBuildJavaFileOnDumbSlave() throws Exception {
        DumbSlave slave = jenkinsPerTest.createOnlineSlave();

        FreeStyleProject project = createFreeStyleProject();
        try {
            project.setAssignedNode(slave);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }

        project.getBuildersList().add(new Maven("verify", null));
        copySingleFileToAgentWorkspace(slave, project, "JavaHelloWorldForDockerITest.java",
                "src/main/java/HelloWorld.java");
        copySingleFileToAgentWorkspace(slave, project, "PomForDockerITest.xml", "pom.xml");
        enableWarnings(project, createTool(new MavenConsole(), ""));

        AnalysisResult result = scheduleSuccessfulBuild(project);
        assertThat(result).isNotNull();
    }

    @Test
    public void shouldBuildCFileOnDumbSlave() throws Exception {
        DumbSlave worker = jenkinsPerTest.createOnlineSlave();
        FreeStyleProject project = createFreeStyleProject();
        try {
            project.setAssignedNode(worker);
        }
        catch (IOException exception) {
            throw new AssertionError(exception);
        }
        buildSuccessfully(project);

        project.getBuildersList().add(new Shell("make"));
        copySingleFileToAgentWorkspace(worker, project, "CHelloWorldMakeFileForDockerITest", "Makefile");
        copySingleFileToAgentWorkspace(worker, project, "CHelloWorldForDockerITest.c", "CHelloWorldForDockerITest.c");
        enableWarnings(project, createTool(new Gcc3(), ""));

        scheduleSuccessfulBuild(project);

        AnalysisResult result = scheduleBuildAndAssertStatus(project, Result.SUCCESS);
        assertThat(result).isNotNull();
    }

    @Test
    public void shouldBuildJavaFileOnDumbSlaveWithSecurityEnabled() throws Exception {
        DumbSlave slave = createDumbSlaveWithEnabledSecurity();
        FreeStyleProject project = createFreeStyleProject();
        try {
            project.setAssignedNode(slave);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }

        project.getBuildersList().add(new Maven("verify", null));
        copySingleFileToAgentWorkspace(slave, project, "JavaHelloWorldForDockerITest.java",
                "src/main/java/HelloWorld.java");
        copySingleFileToAgentWorkspace(slave, project, "PomForDockerITest.xml", "pom.xml");
        enableWarnings(project, createTool(new MavenConsole(), ""));

        AnalysisResult result = scheduleSuccessfulBuild(project);
        assertThat(result).isNotNull();
    }

    public DumbSlave createDumbSlaveWithEnabledSecurity() throws Exception {
            DumbSlave agent = jenkinsPerTest.createOnlineSlave();

            FilePath child = getJenkins().getInstance().getRootPath().child("secrets/filepath-filters.d/30-default.conf");
            child.delete();
            child.write("", "ISO_8859_1");

            Objects.requireNonNull(getJenkins().jenkins.getInjector())
                    .getInstance(AdminWhitelistRule.class).setMasterKillSwitch(false);
            getJenkins().jenkins.save();
            return agent;
    }

    @Test
    public void shouldStartAgent() {
        DumbSlave agent = createAgent();

        assertThat(agent.getWorkspaceRoot().getName()).isEqualTo("workspace");
    }

    private DumbSlave createAgent() {
        try {
            JavaContainer javaContainer = javaDockerRule.get();

            DumbSlave agent = createAgent(javaContainer);
            getJenkins().jenkins.addNode(agent);
            getJenkins().waitOnline(agent);

            return agent;
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }

    }

    private DumbSlave createAgent(final DockerContainer container) throws FormException, IOException {
        return new DumbSlave("docker", "/home/test",
                new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", ""));
    }
}