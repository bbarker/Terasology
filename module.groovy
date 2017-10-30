// TODO: Credit original authors (MaxBorsch PR #2634 and smsunarto PR #2657
// TODO: Also separately credit #2727 by oniatus as inspiration to get going with Gradle/Groovy

/*
// Interactive Dynamic task definition for creating a new module (from scratch, rather than cloning from GitHub)
// Sample command: gradlew createModuleMyStuff
tasks.addRule("Pattern: createModule<ID>,<VERSION>,<AUTHOR>,<DESCRIPTION>") { String taskName ->
    if (taskName.startsWith("createModule")) {

        // Split arguments by comma
        def args = (taskName - 'createModule').tokenize (',')

        // Use provided argument or request from the user
        def createModule_repo = args[0] ?: getUserString ('Enter module name:')
        def createModule_version = args[1] ?: getUserString ('Enter initial module version:')
        def createModule_author = args[2] ?: getUserString ('Enter author name:')
        def createModule_description = args[3] ?: getUserString ('Enter module description:')

        // Here's the actual task definition for each dynamically created task of name taskName
        task (taskName, type: GitInit) {
            description = 'Creates template source for a given module to the local project and preps a local Git repo'

            if (!createModule_repo){
                throw new GradleException ("Aborted createModule! No module name.")
            }

            // Repo name is the dynamic part of the task name
            def repo = createModule_repo
            def parentDir = 'modules'

            // Default GitHub account to use. Supply with -PgithubAccount="TargetAccountName" or via gradle.properties
            def githubHome = 'Terasology'

            // Command line parameter has precedence - if it is set we do not check gradle.properties
            if (project.hasProperty('githubAccount')) {
                githubHome = githubAccount
            } else if (project.hasProperty('alternativeGithubHome')) {
                githubHome =alternativeGithubHome
            }

            def destination = file("$parentDir/$repo")

            // Don't create this repo if we already have a directory by that name (also determines Gradle UP-TO-DATE)
            enabled = !destination.exists()
            println "createModule requested for $repo linked to $githubHome on GitHub - exists already? " + !enabled

            // Do the actual creation if we don't have the directory already
            if (enabled) {

                println "Creating $repo locally"
                destination.mkdir()
                destinationPath = destination

                // TODO: Add in the local mapping to a remote ref definition. Needs support in the Gradle-Git plugin
                //uri = "https://github.com/$githubAccount/" + repo + ".git"
                bare = false

                // Copy in some template stuff for the new module
                doLast {
                    new File(destination, 'build.gradle') << new File(rootDir, 'modules/Core/build.gradle').text
                    // TODO : Add in the logback.groovy from engine\src\test\resources\logback.groovy ? Local dev only, Jenkins will use the one inside engine-tests.jar. Also add to .gitignore
                    def moduleManifest = new File (destination, 'module.txt')
                    def moduleText = new File(templatesDir, 'module.txt').text
                    moduleText = moduleText
                            .replaceAll('MODULENAME', createModule_repo)
                            .replaceAll('VERSION', createModule_version)
                            .replaceAll('AUTHOR', createModule_author)
                            .replaceAll('DESCRIPTION', createModule_description)

                    moduleManifest << moduleText
                    new File(destination, '.gitignore') << new File(templatesDir, '.gitignore').text
                }
            }
        }
    }
}
*/

// We use GrGit for interacting with Git. This gets a hold of it as a dependency like Gradle would
// TODO: Consider if we should do something to fix/suppress the SLF4J warning that gets logged on first usage?
@GrabResolver(name = 'jcenter', root = 'http://jcenter.bintray.com/')
@Grab(group='org.ajoberstar', module='grgit', version='1.9.3')
import org.ajoberstar.grgit.Grgit

import groovy.json.JsonSlurper

// Grab override properties from the gradle.properties file (shared with various Gradle commands)
Properties properties = new Properties()
new File("gradle.properties").withInputStream {
    properties.load(it)
}
//println "Properties: " + properties

// Groovy Elvis operator woo! Defaults to "Terasology" if an override isn't set
githubHome = properties.alternativeGithubHome ?: "Terasology"

//println "githubHome is: $githubHome"

// For keeping a list of modules retrieved so far
modulesRetrieved = []

// Module dependencies we don't want to retrieve as they live in the main Terasology repo
excludedDependencies = ["engine", "Core", "CoreSampleGameplay", "BuilderSampleGameplay"]

/**
 * Primary entry point for retrieving modules, kicks off recursively if needed.
 * @param modules the modules we want to retrieve
 * @param recurse whether to also retrieve dependencies of the desired modules
 */
def retrieve(String[] modules, boolean recurse) {
    println "Now inside retrieve, user (recursively? $recurse) wants: $modules"
    for (String module : modules) {
        println "Starting loop for module $module, are we recursing? $recurse"
        println "Modules retrieved so far: $modulesRetrieved"
        retrieveModule(module, recurse)
        //println "Modules retrieved after recent addition(s): modulesRetrieved"
    }
}

/**
 * Retrieves a single module via Git Clone. Considers whether it exists locally first or if it has already been retrieved this execution.
 * @param module the target module to retrieve
 * @param recurse whether to also retrieve its dependencies (if so then recurse back into retrieve)
 */
def retrieveModule(String module, boolean recurse) {
    File targetDir = new File("modulesTEST/$module") //TODO: Adjust after testing - moduleTEST
    println "Request to retrieve module $module would store it at $targetDir - exists? " + targetDir.exists()

    if (targetDir.exists()) {
        println "That module already had an existing directory locally. If something is wrong with it please delete and try again"
        modulesRetrieved << module
    } else if (modulesRetrieved.contains(module)) {
        println "We already retrieved $module - skipping"
    } else {
        println "Retrieving module $module - if it doesn't appear to exist (typo for instance) you'll get an auth prompt (in case it is private)"
        //noinspection GroovyAssignabilityCheck - GrGit has its own .clone but a warning gets issued for Object.clone
        Grgit.clone dir: targetDir, uri: "https://github.com/$githubHome/${module}.git"
        modulesRetrieved << module

        // TODO: Temporary until build.gradle gets removed from module directories (pending Cervator work)
        File targetBuildGradle = new File(targetDir, 'build.gradle')
        targetBuildGradle.delete()
        targetBuildGradle << new File('modulesTEST/Core/build.gradle').text //TODO: Adjust after testing - moduleTEST

        File moduleManifest = new File(targetDir, 'module.txt')
        if (!moduleManifest.exists()) {
            def moduleText = new File("templates/module.txt").text
            moduleManifest << moduleText.replaceAll('MODULENAME', module)
            println "WARNING: Module $module did not have a module.txt! One was created, please review and submit to GitHub"
        }

        // Recurse deeper if we are retrieving dependencies as well
        if (recurse) {
            def foundDependencies = readModuleDependencies(new File(targetDir, "module.txt"))
            if (foundDependencies.length == 0) {
                println "Module $module did not appear to have any dependencies we need to worry about"
            } else {
                println "Module $module has the following module dependencies we care about: $foundDependencies"
                String[] uniqueDependencies = foundDependencies - modulesRetrieved
                println "After removing dupes already retrieved we have the remaining dependencies left: $uniqueDependencies"
                if (uniqueDependencies.length > 0) {
                    retrieve(uniqueDependencies, true)
                }
            }
        }
    }
}

/**
 * Reads a given module info file to figure out which if any dependencies it has. Filters out any already retrieved.
 * @param targetModuleInfo the target file to check (a module.txt file or similar)
 * @return a String[] containing the next level of dependencies, if any
 */
String[] readModuleDependencies(File targetModuleInfo) {
    def qualifiedDependencies = []
    if (!targetModuleInfo.exists()) {
        println "The module info file did not appear to exist - can't calculate dependencies"
        return qualifiedDependencies
    }

    def slurper = new JsonSlurper()
    def moduleConfig = slurper.parseText(targetModuleInfo.text)
    for (dependency in moduleConfig.dependencies) {
        if (excludedDependencies.contains(dependency.id)) {
            println "Skipping listed dependency $dependency as it is in the exclude list (shipped with primary project)"
        } else {
            println "Accepting listed dependency $dependency"
            qualifiedDependencies << dependency.id
        }
    }
    return qualifiedDependencies
}

/**
 * Accepts input from the user, showing a descriptive prompt.
 * @param prompt the prompt to show the user
 */
def getUserString (String prompt) {
    println ('\n*** ' + prompt + '\n')

    def reader = new BufferedReader(new InputStreamReader(System.in)) // Note: Do not close reader, it will close System.in (Big no-no)

    return reader.readLine()
}

/**
 * Simply prints usage information.
 */
def printUsage() {
    println ""
    println "Utility script for interacting with modules. Available sub commands:"
    println "- 'get' - retrieves one or more modules in source form (separate with spaces)"
    println "- 'recurse' - retrieves the given module(s) *and* their dependencies in source form"
    println ""
    println "Example: 'groovyw module recurse GooeysQuests Sample' - would retrieve those modules plus their dependencies"
    println "*NOTE*: Module names are case sensitive"
    println ""
    println "If you omit further arguments beyond the sub command you'll be prompted for details"
    println "After changing modules available in your workspace rerun 'gradlew idea' and/or refresh your IDE"
    println ""
    println "For advanced usage see project documentation. For instance you can provide an alternative GitHub home"
    println "A gradle.properties file (one exists under '/templates' in an engine workspace) can provide such overrides"
    println ""
}

// Main bit of logic handling the entry points to this script - defers actual work to dedicated methods
//println "Args: $args"
if (args.length == 0) {
    printUsage()
} else {
    def recurse = false
    switch (args[0]) {
        case 'usage':
            printUsage()
            break
        case "recurse":
            recurse = true
            println "We're retrieving recursively (all the things depended on too)"
            // We just fall through here to the get logic after setting a boolean
        //noinspection GroovyFallthrough
        case "get":
            println "Preparing to get one or more modules"
            if (args.length == 1) {
                // User hasn't supplied any module names, so ask
                def moduleString = getUserString('Enter Module Name(s - separate multiple with spaces, CapiTaliZation MatterS): ')
                println "User wants: $moduleString"
                // Split it on whitespace
                String[] moduleList = moduleString.split("\\s+")
                println "Now in an array: $moduleList"
                retrieve moduleList, recurse
            } else {
                // User has supplied one or more module names, so pass them forward (skipping the "get" arg)
                def adjustedArgs = args.drop(1)
                println "Adjusted args: $adjustedArgs"
                retrieve adjustedArgs, recurse
            }
            println "All done retrieving requested modules: $modulesRetrieved"
            break
        case "create":
            println "We're doing a create"
            String someParam = getUserString("Please enter a thing")
            println "User return was: $someParam"
            break
        default:
            println "UNRECOGNIZED COMMAND - please try again or use 'groovyw module usage' for help"
    }
}
