package com.netflix.nebula.lint.rule.dependency

import com.netflix.nebula.lint.rule.GradleDependency
import com.netflix.nebula.lint.rule.GradleLintRule
import com.netflix.nebula.lint.rule.GradleModelAware
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier

abstract class AbstractDuplicateDependencyClassRule extends GradleLintRule implements GradleModelAware {
    String description = 'classpaths with duplicate classes may break unpredictably depending on the order in which dependencies are provided to the classpath'

    Set<Configuration> directlyUsedConfigurations = [] as Set
    Set<ModuleVersionIdentifier> ignoredDependencies = [] as Set

    abstract protected List<ModuleVersionIdentifier> moduleIds(Configuration conf)

    protected static List<ModuleVersionIdentifier> firstOrderModuleIds(Configuration conf) {
        return conf.resolvedConfiguration.firstLevelModuleDependencies.collect { it.module.id }
    }

    protected static List<ModuleVersionIdentifier> transitiveModuleIds(Configuration conf) {
        // Classifier artifacts (javadoc/sources/etc.) can sometimes be resolved implicitly causing duplicates, we're interested only in distinct modules
        return conf.resolvedConfiguration.resolvedArtifacts
                .collect { it.moduleVersion.id }
                .unique { it.module.toString() }
    }

    @Override
    void visitGradleDependency(MethodCallExpression call, String conf, GradleDependency dep) {
        if (ignored) {
            ignoredDependencies.add(dep.toModuleVersion())
        } else {
            def confModel = project.configurations.findByName(conf)
            if (confModel) {
                directlyUsedConfigurations.add(confModel)
            }
        }
    }

    @Override
    protected void visitClassComplete(ClassNode node) {
        for (Configuration conf : directlyUsedConfigurations) {
            if (DependencyService.forProject(project).isResolved(conf)) {
                def moduleIds = moduleIds(conf)
                def dependencyService = Class.forName('com.netflix.nebula.lint.rule.dependency.DuplicateDependencyService').newInstance(project)
                def checkForDuplicates = dependencyService.class.methods.find { it.name == 'checkForDuplicates'}
                def violations = checkForDuplicates.invoke(dependencyService, moduleIds, conf, ignoredDependencies) as List<String>
                violations.each { message ->
                    addBuildLintViolation(message)
                }
            }
        }
    }
}