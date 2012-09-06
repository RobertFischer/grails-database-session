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

The spec for Session requires that invalidated sessions should throw exceptions if you so much as look at them. 
If you have some kind of buggy library that depends non-conforming behavior of the Session implementation _\*coughSpringSecuritycough\*_, 
you can disable the invalidated session check by setting the `config.grails.plugin.databasesession.ignoreinvalid` property. If you want to
set it for a particular Session method, you can set a child of that property. So, to ignore the invalid state of the session for calls to 
the `foo` method, you would set the `config.grails.plugin.databasesession.ignoreinvalid.foo` configuration property in your application.
The value for this property should be true-ish. Your best best is the boolean `true`, but there is some flexibility for other popular 
variations on true.

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
