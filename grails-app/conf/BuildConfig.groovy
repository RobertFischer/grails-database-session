grails.project.work.dir = 'target'
grails.project.docs.output.dir = 'docs/manual' // for backwards-compatibility, the docs are checked into gh-pages branch

grails.project.dependency.resolution = {

	inherits 'global'
	log 'warn'

	repositories {
		mavenLocal()
		mavenCentral()
		grailsCentral()
		ebr()
	}
	dependencies {
		compile 'com.google.guava:guava:12.0'
		//compile 'c3p0:c3p0:9.1.2'
		//compile 'commons-io:commons-io:2.3'
	}

}
