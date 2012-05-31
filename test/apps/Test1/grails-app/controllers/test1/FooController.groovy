package test1

class FooController {

	static scope = 'singleton'

	def grailsApplication

	def index() { 
		log.debug("Beginning processing foo/index...")
		def serverName = "${grailsApplication.metadata['app.name']} - ${request.serverPort} - ${request.getRequestURI()}"
		def lastAt = session.lastAt
		session.lastAt = serverName
		log.debug("Rendering view...")
		return [
			serverName: serverName,
			lastAt: lastAt,
			log: log
		]
	}

}
