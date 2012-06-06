Grails Database Session Plugin
===============================


This plugin solves the problem of session affinity by allowing you to transparently store HTTP session data into a shared datastore. 
By default, it stores the
session into the same database as the application data, but this is fully configurable.

This plugin is based on the [canonical version by Burt Beckwith](http://grails.org/plugin/database-session), although it has been almost entirely
rewritten. A more extensive discussion of this plugin (including a tour under the hood) can be found in 
[the June 2012 GroovyMag](http://www.groovymag.com/main.issues.description/id=46/).

Usage
-----

To use this plugin, simply add it to your `BuildConfig.groovy`.

First, add `mavenRepo 'http://repo.smokejumperit.com'` into the repositories. Then add `compile 'RobertFischer:database-session:1.2.0'` into the
section for plugins. Done and done!

Configuration
---------------

By default, the plugin is enabled in non-development environments. If you would like to change that behavior, simply set the configuration
value of `config.grails.plugin.databasesession.enabled` to boolean `true` (Groovy-truth is insufficient) and the plugin will be enabled; set it
to boolean `false` (Groovy-false is insufficient) and the plugin will be disabled. 

If you want to use a different SQL data source, the easiest way is to set the children of the `config.grails.plugin.databasesession.dbConfig` 
property. The four values you can set are:
* *url*: The connection string (required)
* *driverClassName*: The class name of the driver (required if the DriverManager cannot inferred it from the url)
* *username*: The username for the connection (optional)
* *password*: The password for the connection (optional)

Spring Bean Configuration
--------------------------

The Persistence mechanism used is specified by the `sessionPersister` Spring Bean, which is expected to be of type 
[`Persister`](https://github.com/RobertFischer/grails-database-session/blob/master/src/java/grails/plugin/databasesession/Persister.java). 
If you want to
override the persistence behavior, simply overwrite that bean in `resources.groovy`: 

```groovy
beans = {
  sessionPersister(my.company.MySessionPersister)
}
```

If you want to see what other beans are available to be overwritten, take a look at [`doWithSpring`](https://github.com/RobertFischer/grails-database-session/blob/master/DatabaseSessionGrailsPlugin.groovy#L58).
