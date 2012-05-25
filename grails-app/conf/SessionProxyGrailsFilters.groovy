import grails.plugin.databasesession.*
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.ForwardingSortedMap
import com.google.common.collect.ImmutableSortedMap.Builder

import javax.servlet.http.HttpSession

class SessionProxyFilter {

	Persister sessionPersister

  private static final class SessionHash extends ForwardingSortedMap<String,Integer> {

    private final SortedMap<String,Integer> data;

    SessionHash(HttpSession session) {
      def builder = ImmutableSortedMap.builder();
      Collections.asList(session.getAttributeNames()).each { name -> 
        Object value = session.getAttribute(name);
        builder.put(attr, value == null ? 0 : value.hashCode());
      }

			// Non-attribute values that we would need to persist
      builder.put("\n\tmaxInactiveInterval", session.getMaxInactiveInterval());

      data = builder.build();
    }

    SortedMap<String,Integer> delegate() {
      return data;
    }
  }

  private final Cache<String, SessionHash> sessions = CacheBuilder.newBuilder()
    .expireAfterAccess(1, TimeUnit.HOURS)
    .concurrencyLevel(Math.max(1, Runtime.runtime.availableProcessors() / 2))
    .initialCapacity(2)
    .softValues()
    .build();

	def filters = {
		sessionProxyFilter(controller:'*', action:'*') {
			before = {
				if(session == null) return;
				log.debug("Storing state info for $session.id (${session.getClass().name})");
				try {
					sessions.put(session.id, new SessionHash(session));
				} catch(IllegalStateException ise) {
					log.debug("Ignoring session $session.id (${session.getClass().name}): it is invalid", ise)
				}
			}

			afterView = {
				if(session == null) return
				log.debug("Persisting session for $session.id (${session.getClass().name})")
	
				try {
					def hash = sessions.remove(session.id)
					if(hash == null || hash != new SessionHash(session)) {
						sessionPersister.persistSession(SessionData.fromSession(session))
					}
				} catch(IllegalStateException ise) {
					log.debug("Ignoring session $session.id (${session.getClass().name}): it is invalid", ise)
				}
			}
		}
	}

}
