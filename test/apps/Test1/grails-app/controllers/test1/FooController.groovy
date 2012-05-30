package test1

class FooController {

	static scope = 'singleton'

	def grailsApplication

	def index() { 
		def serverName = grailsApplication.metadata['app.name']
		def lastAt = session.lastAt
		session.lastAt = serverName
		return [
			serverName: serverName,
			lastAt: lastAt
		]
	}

}
