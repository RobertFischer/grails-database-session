package grails.plugin.databasesession;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionBindingListener; 
import javax.servlet.http.HttpSessionActivationListener; 

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.Iterators;

/**
 * @author Burt Beckwith
 * @author Robert Fischer
 */
@SuppressWarnings("deprecation")
public class SessionProxy implements HttpSession {

	private final Persister _persister;
	private final String _sessionId;
	private final ServletContext _servletContext;
	private final Map<String,Serializable> attrs;
	private final boolean _isNew;
	private final long _createdAt;
	private final HttpSessionEvent sessionEvent = new HttpSessionEvent(this); // Might as well cache this
	private volatile long _lastAccessedAt;
	private volatile boolean _invalidated;
	private volatile long _maxInactiveInterval;
	

	/**
	 * Constructor. Note that you should call {@link #fireSessionActivationListeners()} after construction is complete.
	 *
	 * @param servletContext the ServletContext
	 * @param persister the persister
	 * @param sessionId session id
	 */
	public SessionProxy(ServletContext servletContext, Persister persister, String sessionId) {
		_servletContext = servletContext;
		_persister = persister;
		_sessionId = sessionId;

		_invalidated = persister.isValid(sessionId);

		final SessionData data = persister.getSessionData(sessionId);
		_attrs = new ConcurrentHashMap<String,Serializable>(data.attrs);
		_createdAt = data.createdAt;
		_lastAccessedAt = data.lastAccessedAt;
		_isNew = data.isNew;
		_maxInactiveInterval = data.maxInactiveInterval;
	}

	/**
	* Mapping back to the {@link SessionData} holder.
	*/
	public SessionData toData() {
		return new SessionData(
			_sessionId, _attrs, _createdAt, _lastAccessedAt, _maxInactiveInterval, _isNew
		);
	}

	public void fireSessionActivationListeners() {
		for(Serializable value : _attrs.values()) {
			if(value instanceof HttpSessionActivationListener) {
				((HttpSessionActivationListener)value).sessionDidActivate(event);
			}
		}
	}

	public void fireSessionPassivationListeners() {
		for(Serializable value : _attrs.values()) {
			if(value instanceof HttpSessionActivationListener) {
				((HttpSessionActivationListener)value).sessionWillPassivate(event);
			}
		}
	}

	public void checkAccess() { 
		if(_invalidated) {
			throw new IllegalStateException("Session " + _sessionId + " is invalid; cannot access/modify it.");
		}
		final long lastAccess = _lastAccessedAt;
		if(lastAccess + (_maxInactiveInterval*1000L) < System.currentTimeMillis()) {
			invalidate();
			throw new IllegalStateException(
				"Session " + _sessionId + " (last accessed at " + new Date(lastAccess) + ") is invalid due to age"
			);
		}
		_lastAccessedAt = System.currentTimeMillis();
	}

	@Override
	public Serializable getAttribute(String name) {
		checkAccess();
		return _attrs.get(name);
	}

	@Override @Deprecated
	public Serializable getValue(String name) {
		return getAttribute(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		checkAccess();
		return Iterators.asEnumeration(_attrs.keySet());
	}

	@Override @Deprecated
	public String[] getValueNames() {
		checkAccess();
		return _attrs.keySet().toArray(new String[0]);
	}

	@Override
	public void setAttribute(String name, Object value) {
		checkAccess();
		if(name == null) throw new IllegalArgumentException("Cannot store a null key into the session");
		if(value == null) {
			removeAttribute(name);
		} else {
			final Serializable oldValue;
			try {
				oldValue = put(name, (Serializable)value);
			} catch(ClassCastException cce) {
				throw new IllegalStateException("Can only set Serializable values into the session (tried to add: " + value.getClass() + ")");
			}
			if(oldValue != null && oldValue instanceof HttpSessionBindingListener) {
				((HttpSessionBindingListener)oldValue).valueUnbound(
					new HttpSessionBindingEvent(this, name, oldValue);
				);
			}
			if(value instanceof HttpSessionBindingListener) {
				((HttpSessionBindingListener)value).valueBound(
					new HttpSessionBindingEvent(this, name, value);
				);
			}
		}
	}

	@Override @Deprecated
	public void putValue(String name, Object value) {
		setAttribute(name, value);
	}

	@Override
	public void removeAttribute(String name) {
		checkAccess();
		Serializable value = remove(name);
		if(value != null && value instanceof HttpSessionBindingListener) {
			((HttpSessionBindingListener)value).valueUnbound(
				new HttpSessionBindingEvent(this, name, value);
			);
		}
		return;
	}

	@Override @Deprecated
	public void removeValue(String name) {
		removeAttribute(name);
	}

	@Override
	public long getCreationTime() {
		checkAccess();
		return _createdAt;
	}

	@Override
	public String getId() {
		return _sessionId;
	}

	@Override
	public long getLastAccessedTime() {
		checkAccess();
		return _lastAccessedAt;
	}

	@Override
	public ServletContext getServletContext() {
		return _servletContext;
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		_maxInactiveInterval = interval;
	}

	@Override
	public int getMaxInactiveInterval() {
		return _maxInactiveInterval;
	}

	@Override @Deprecated
	public HttpSessionContext getSessionContext() {
		return new HttpSessionContext() {
			public HttpSession getSession(String sessionId) {
				return new SessionProxy(_servletContext, _persister, sessionId);
			}
			public Enumeration<String> getIds() {
				return Iterators.asEnumeration(_persister.getSessionIds());
			}
		};
	}

	@Override
	public void invalidate() {
		checkAccess();
		_invalidated = true;
		// A race condition *could* result in a session being invalidated twice
		_persister.invalidate(_sessionId); 
	}
	
	@Override
	public boolean isNew() {
		checkAccess();
		return _isNew;
	}

}
