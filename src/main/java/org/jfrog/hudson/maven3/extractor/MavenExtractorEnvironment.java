/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.maven3.extractor;

import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.PlexusModuleContributor;
import hudson.maven.PlexusModuleContributorFactory;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.remoting.Which;
import hudson.scm.NullChangeLogParser;
import hudson.scm.NullSCM;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;
import org.jfrog.hudson.ArtifactoryRedeployPublisher;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.action.ActionableHelper;
import org.jfrog.hudson.maven3.ArtifactoryMaven3NativeConfigurator;
import org.jfrog.hudson.release.ReleaseAction;
import org.jfrog.hudson.util.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Class for setting up the {@link Environment} for a {@link MavenModuleSet} project. Responsible for adding the new
 * maven opts with the location of the plugin.
 *
 * @author Tomer Cohen
 */
public class MavenExtractorEnvironment extends Environment {

    private final ArtifactoryRedeployPublisher publisher;
    private final MavenModuleSetBuild build;
    private final ArtifactoryMaven3NativeConfigurator resolver;
    private final BuildListener buildListener;
    private final EnvVars envVars;
    private String propertiesFilePath;

    // the build env vars method may be called again from another setUp of a wrapper so we need this flag to
    // attempt only once certain operations (like copying file or changing maven opts).
    private boolean initialized;

    public MavenExtractorEnvironment(MavenModuleSetBuild build, ArtifactoryRedeployPublisher publisher,
                                     ArtifactoryMaven3NativeConfigurator resolver, BuildListener buildListener)
            throws IOException, InterruptedException {
        this.buildListener = buildListener;
        this.build = build;
        this.publisher = publisher;
        this.resolver = resolver;
        this.envVars = build.getEnvironment(buildListener);
    }

    @Override
    public void buildEnvVars(Map<String, String> env) {

        if (build.getWorkspace() == null) {
            // HAP-274 - workspace might not be initialized yet (this method will be called later in the build lifecycle)
            return;
        }

        //If an SCM is configured
        if (!initialized && !(build.getProject().getScm() instanceof NullSCM)) {
            //Handle all the extractor info only when a checkout was already done
            boolean checkoutWasPerformed = true;
            try {
                Field scmField = AbstractBuild.class.getDeclaredField("scm");
                scmField.setAccessible(true);
                Object scmObject = scmField.get(build);
                //Null changelog parser is set when a checkout wasn't performed yet
                checkoutWasPerformed = !(scmObject instanceof NullChangeLogParser);
            } catch (Exception e) {
                buildListener.getLogger().println("[Warning] An error occurred while testing if the SCM checkout " +
                        "has already been performed: " + e.getMessage());
            }
            if (!checkoutWasPerformed) {
                return;
            }
        }

        // if not valid Maven version don't modify the environment
        if (!isMavenVersionValid()) {
            return;
        }
        env.put(ExtractorUtils.EXTRACTOR_USED, "true");

        if (!initialized) {
            try {
                PublisherContext publisherContext = null;
                if (publisher != null) {
                    publisherContext = createPublisherContext(publisher, build);
                }

                ResolverContext resolverContext = null;
                if (resolver != null) {
                    Credentials resolverCredentials = CredentialResolver.getPreferredResolver(
                            resolver, publisher, resolver.getArtifactoryServer());
                    resolverContext = new ResolverContext(resolver.getArtifactoryServer(), resolver.getDetails(),
                            resolverCredentials);
                }

                ArtifactoryClientConfiguration configuration = ExtractorUtils.addBuilderInfoArguments(
                        env, build, buildListener, publisherContext, resolverContext);
                propertiesFilePath = configuration.getPropertiesFile();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            initialized = true;
        }

        env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propertiesFilePath);
    }

    private boolean isMavenVersionValid() {
        try {
            return MavenVersionHelper.isAtLeastResolutionCapableVersion(build, envVars, buildListener);
        } catch (Exception e) {
            throw new RuntimeException("Unable to determine Maven version", e);
        }
    }

    private PublisherContext createPublisherContext(ArtifactoryRedeployPublisher publisher, AbstractBuild build) {
        ReleaseAction release = ActionableHelper.getLatestAction(build, ReleaseAction.class);
        ServerDetails server = publisher.getDetails();
        if (release != null) {
            // staging build might change the target deployment repository
            String stagingRepoKey = release.getStagingRepositoryKey();
            if (!StringUtils.isBlank(stagingRepoKey) && !stagingRepoKey.equals(server.repositoryKey)) {
                server = new ServerDetails(server.artifactoryName, server.getArtifactoryUrl(), stagingRepoKey,
                        server.snapshotsRepositoryKey, server.downloadRepositoryKey);
            }
        }

        PublisherContext context = new PublisherContext.Builder().artifactoryServer(publisher.getArtifactoryServer())
                .serverDetails(server).deployerOverrider(publisher).runChecks(publisher.isRunChecks())
                .includePublishArtifacts(publisher.isIncludePublishArtifacts())
                .violationRecipients(publisher.getViolationRecipients()).scopes(publisher.getScopes())
                .licenseAutoDiscovery(publisher.isLicenseAutoDiscovery())
                .discardOldBuilds(publisher.isDiscardOldBuilds()).deployArtifacts(publisher.isDeployArtifacts())
                .includesExcludes(publisher.getArtifactDeploymentPatterns())
                .skipBuildInfoDeploy(!publisher.isDeployBuildInfo())
                .includeEnvVars(publisher.isIncludeEnvVars()).envVarsPatterns(publisher.getEnvVarsPatterns())
                .discardBuildArtifacts(publisher.isDiscardBuildArtifacts())
                .matrixParams(publisher.getMatrixParams()).evenIfUnstable(publisher.isEvenIfUnstable())
                .enableIssueTrackerIntegration(publisher.isEnableIssueTrackerIntegration())
                .aggregateBuildIssues(publisher.isAggregateBuildIssues())
                .aggregationBuildStatus(publisher.getAggregationBuildStatus())
                .integrateBlackDuck(publisher.isBlackDuckRunChecks(), publisher.getBlackDuckAppName(),
                        publisher.getBlackDuckAppVersion(), publisher.getBlackDuckReportRecipients(),
                        publisher.getBlackDuckScopes(), publisher.isBlackDuckIncludePublishedArtifacts(),
                        publisher.isAutoCreateMissingComponentRequests(),
                        publisher.isAutoDiscardStaleComponentRequests())
                .filterExcludedArtifactsFromBuild(publisher.isFilterExcludedArtifactsFromBuild())
                .build();

        return context;
    }

    @Extension
    public static class ArtifactoryPlexusContributor extends PlexusModuleContributorFactory {

        private static final String INCLUDED_FILES = "*.jar";
        private static final String EXCLUDED_FILES = "classes.jar, *ivy*, *gradle*";

        @Override
        public PlexusModuleContributor createFor(AbstractBuild<?, ?> context) throws IOException, InterruptedException {
            if (MavenExtractorHelper.isDisabled(context)) {
                return null;
            }

            File maven3ExtractorJar = Which.jarFile(BuildInfoRecorder.class);
            FilePath dependenciesDirectory = PluginDependencyHelper.getActualDependencyDirectory(context, maven3ExtractorJar);

            FilePath[] files = dependenciesDirectory.list(INCLUDED_FILES, EXCLUDED_FILES);
            List<FilePath> jars = Lists.newArrayList();
            Collections.addAll(jars, files);

            return PlexusModuleContributor.of(jars);
        }
    }
}
