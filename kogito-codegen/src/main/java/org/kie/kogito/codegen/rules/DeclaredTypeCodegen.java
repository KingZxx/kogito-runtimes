/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.codegen.rules;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.drools.compiler.builder.impl.KnowledgeBuilderConfigurationImpl;
import org.drools.compiler.compiler.DroolsError;
import org.drools.compiler.compiler.PackageRegistry;
import org.drools.compiler.kproject.ReleaseIdImpl;
import org.drools.compiler.lang.descr.CompositePackageDescr;
import org.drools.compiler.lang.descr.PackageDescr;
import org.drools.core.io.impl.FileSystemResource;
import org.drools.modelcompiler.builder.GeneratedFile;
import org.drools.modelcompiler.builder.ModelBuilderImpl;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.internal.builder.CompositeKnowledgeBuilder;
import org.kie.kogito.codegen.AbstractGenerator;
import org.kie.kogito.codegen.ApplicationSection;
import org.kie.kogito.codegen.ApplicationConfigGenerator;
import org.kie.kogito.codegen.KogitoPackageSources;
import org.kie.kogito.codegen.context.KogitoBuildContext;
import org.kie.kogito.codegen.rules.config.RuleConfigGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;

public class DeclaredTypeCodegen extends AbstractGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeclaredTypeCodegen.class);

    public static DeclaredTypeCodegen ofPath(KogitoBuildContext context, Path basePath) {
        try {
            Stream<File> files = Files.walk(basePath).map(Path::toFile);
            Set<Resource> resources = toResources(files);
            return new DeclaredTypeCodegen(context, resources);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DeclaredTypeCodegen ofFiles(KogitoBuildContext context, Collection<File> files) {
        return new DeclaredTypeCodegen(context, toResources(files.stream()));
    }

    public static DeclaredTypeCodegen ofResources(KogitoBuildContext context, Collection<Resource> resources) {
        return new DeclaredTypeCodegen(context, resources);
    }

    private static Set<Resource> toResources(Stream<File> files) {
        return files.map(FileSystemResource::new).peek(r -> r.setResourceType(typeOf(r))).filter(r -> r.getResourceType() != null).collect(Collectors.toSet());
    }

    private static ResourceType typeOf(FileSystemResource r) {
        for (ResourceType rt : resourceTypes) {
            if (rt.matchesExtension(r.getFile().getName())) {
                return rt;
            }
        }
        return null;
    }

    private static final ResourceType[] resourceTypes = {
            ResourceType.DRL
    };
    private final Collection<Resource> resources;

    /**
     * used for type-resolving during codegen/type-checking
     */
    private ClassLoader contextClassLoader;

    private DeclaredTypeCodegen(KogitoBuildContext context, Collection<Resource> resources) {
        super(context);
        this.resources = resources;
        this.contextClassLoader = getClass().getClassLoader();
    }

    @Override
    public ApplicationSection section() {
        return null;
    }

    public List<org.kie.kogito.codegen.GeneratedFile> generate() {
        ReleaseIdImpl dummyReleaseId = new ReleaseIdImpl("dummy:dummy:0.0.0");

        KnowledgeBuilderConfigurationImpl configuration =
                new KnowledgeBuilderConfigurationImpl(contextClassLoader);

        ModelBuilderImpl<KogitoPackageSources> modelBuilder = new ModelBuilderImpl<KogitoPackageSources>(
                KogitoPackageSources::dumpPojos, configuration, dummyReleaseId, true, true) {

            @Override
            protected void buildOtherDeclarations(Collection<CompositePackageDescr> packages) {
                // skip full processing
            }

            @Override
            protected void compileKnowledgePackages(PackageDescr packageDescr, PackageRegistry pkgRegistry) {
                // skip full processing
            }
        };

        CompositeKnowledgeBuilder batch = modelBuilder.batch();
        resources.forEach(f -> batch.add(f, f.getResourceType()));

        try {
            batch.build();
        } catch (RuntimeException e) {
            for (DroolsError error : modelBuilder.getErrors().getErrors()) {
                LOGGER.error(error.toString());
            }
            LOGGER.error(e.getMessage());
            throw new RuleCodegenError(e, modelBuilder.getErrors().getErrors());
        }

        if (modelBuilder.hasErrors()) {
            for (DroolsError error : modelBuilder.getErrors().getErrors()) {
                LOGGER.error(error.toString());
            }
            throw new RuleCodegenError(modelBuilder.getErrors().getErrors());
        }

        List<GeneratedFile> modelFiles = new ArrayList<>();

        for (KogitoPackageSources pkgSources : modelBuilder.getPackageSources()) {
            pkgSources.collectGeneratedFiles(modelFiles);
        }

        return modelFiles.stream()
                .filter(Objects::nonNull)
                .map(f -> new org.kie.kogito.codegen.GeneratedFile(
                        org.kie.kogito.codegen.GeneratedFile.Type.DECLARED_TYPE,
                        f.getPath(), f.getData())).collect(toList());
    }

    @Override
    public void updateConfig(ApplicationConfigGenerator cfg) {
        cfg.withRuleConfig(new RuleConfigGenerator(context()));
    }

    public DeclaredTypeCodegen withClassLoader(ClassLoader projectClassLoader) {
        this.contextClassLoader = projectClassLoader;
        return this;
    }
}
