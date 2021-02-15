/*
 * Copyright (c) 2021, Gluon
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.gluonhq;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "runagent", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class NativeRunAgentMojo extends NativeBaseMojo {

    private static final String AGENTLIB_NATIVE_IMAGE_AGENT_STRING = "-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image";

    @Parameter(readonly = true, required = true, defaultValue = "${basedir}/pom.xml")
    String pom;

    @Parameter(readonly = true, required = true, defaultValue = "${basedir}/src/main/resources/META-INF/native-image")
    String agentDir;

    @Parameter(readonly = true, required = true, defaultValue = "${project.basedir}/agentPom.xml")
    String agentPom;

    @Override
    public void execute() throws MojoExecutionException {
        final InvocationRequest invocationRequest = new DefaultInvocationRequest();
        invocationRequest.setProfiles(project.getActiveProfiles().stream()
                .map(Profile::getId)
                .collect(Collectors.toList()));
        invocationRequest.setProperties(session.getRequest().getUserProperties());

        // 1. Create/Clear directory for config files
        File agentDirFile = new File(agentDir);
        if (agentDirFile.exists()) {
            // TODO: Delete files
            // otherwise it keeps merging results from different runs
            // and config files might get outdated.
        } else {
            agentDirFile.mkdirs();
        }

        // 2. Create modified pom
        File agentPomFile = new File(agentPom);
        try (InputStream is = new FileInputStream(new File(pom))) {
            // 3. Create model from current pom
            Model model = new MavenXpp3Reader().read(is);

            model.getBuild().getPlugins().stream()
                    .filter(p -> p.getGroupId().equalsIgnoreCase("org.openjfx") &&
                            p.getArtifactId().equalsIgnoreCase("javafx-maven-plugin"))
                    .findFirst()
                    .ifPresentOrElse(p -> {
                        // 4. Modify configuration
                        p.setConfiguration(modifyConfiguration(p.getConfiguration()));
                    }, () -> getLog().warn("No JavaFX plugin found",
                            new MojoExecutionException("No JavaFX plugin found")));

            // 5. Serialize new pom
            try (OutputStream os = new FileOutputStream(agentPomFile)) {
                new MavenXpp3Writer().write(os, model);
            }
        } catch (Exception e) {
            if (agentPomFile.exists()) {
                agentPomFile.delete();
            }
            throw new MojoExecutionException("Error generating agent pom", e);
        }

        invocationRequest.setPomFile(agentPomFile);
        invocationRequest.setGoals(Collections.singletonList("javafx:run"));

        final Invoker invoker = new DefaultInvoker();
        // 6. Execute:
        try {
            final InvocationResult invocationResult = invoker.execute(invocationRequest);
            if (invocationResult.getExitCode() != 0) {
                throw new MojoExecutionException("Error, javafx:run failed", invocationResult.getExecutionException());
            }
        } catch (MavenInvocationException e) {
            e.printStackTrace();
            throw new MojoExecutionException("Error", e);
        } finally {
            if (agentPomFile.exists()) {
                agentPomFile.delete();
            }
        }
    }

    private Object modifyConfiguration(Object config) {
        // 1. Change executable to GRAALVM_HOME
        Xpp3Dom dom = (Xpp3Dom) config;
        Xpp3Dom executable = dom.getChild("executable");
        String graalVMJava = getGraalvmHome().get() + "/bin/java";
        if (executable == null) {
            Xpp3Dom d = new Xpp3Dom("executable");
            d.setValue(graalVMJava);
            dom.addChild(d);
        } else {
            executable.setValue(graalVMJava);
        }

        // 2. Add native-image-agent option
        Xpp3Dom options = dom.getChild("options");
        if (options == null) {
            Xpp3Dom os = new Xpp3Dom("options");
            Xpp3Dom o = new Xpp3Dom("option");
            o.setValue(AGENTLIB_NATIVE_IMAGE_AGENT_STRING);
            os.addChild(o);
            dom.addChild(os);
        } else {
            Stream.of(options.getChildren())
                    .filter(i -> i.getValue().contains("native-image-agent"))
                    .findFirst()
                    .ifPresentOrElse(i ->
                            i.setValue(AGENTLIB_NATIVE_IMAGE_AGENT_STRING),
                            () -> {
                                Xpp3Dom o = new Xpp3Dom("option");
                                o.setValue(AGENTLIB_NATIVE_IMAGE_AGENT_STRING);
                                options.addChild(o);
                            });
        }
        return config;
    }
}
