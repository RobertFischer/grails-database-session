package grails.plugin.databasesession

import java.util.concurrent.*

import grails.plugin.databasesession.*

class SessionProxyGrailsFilters {

	Persister sessionPersister

	def filters = {
		sessionProxyFilter(controller:'*', action:'*') {
			before = {
				if(session == null) return;
				log.debug("Storing state info for $session.id (${session.getClass().name})");
				try {
					request.setAttribute("GRAILS_DB_SESSION.hash", new SessionHash(session))
				} catch(IllegalStateException ise) {
					log.debug("Ignoring session $session.id (${session.getClass().name}): it is invalid", ise)
				}
			}

			afterView = {
				if(session == null) return
				log.debug("Persisting session for $session.id (${session.getClass().name})")
	
				try {
					def hash = request.getAttribute("GRAILS_DB_SESSION.hash")
					if(hash == null || hash != new SessionHash(session)) { // Only persist if there might have been a change
						// Only persist if it A) was in the database, or B) the attributes aren't empty
						if(sessionPersister.isValid(session.id) || !Collections.list(session.attributeNames).isEmpty()) {
							sessionPersister.persistSession(SessionData.fromSession(session))
						} else {
							log.debug("Not persisting session ($session.id) because it isn't valid and has empty attributes");
						}
					} else {
						log.debug("Not persisting session ($session.id) because it hasn't changed");
					}
				} catch(IllegalStateException ise) {
					log.debug("Ignoring session $session.id (${session.getClass().name}): it is invalid", ise)
				} catch(Exception e) {
					log.error("Unknown exception while persisting session $session.id", e)
				}
			}
		}
	}

}
