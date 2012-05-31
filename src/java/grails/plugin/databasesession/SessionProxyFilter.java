package grails.plugin.databasesession;

import java.io.IOException;

import java.util.UUID;
import java.util.Collections;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers a request wrapper that intercepts getSession() calls and returns a
 * database-backed implementation.
 *
 * @author Burt Beckwith
 * @author Robert Fischer
 */
public class SessionProxyFilter extends OncePerRequestFilter {

	protected static final String COOKIE_NAME = "SessionProxyFilter_SessionId";

	private Persister persister;

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Override
	protected void doFilterInternal(final HttpServletRequest request,
			final HttpServletResponse response, final FilterChain chain)
					throws ServletException, IOException {
		log.debug("Executing the SessionProxyFilter");

		final HttpServletRequest requestForChain;

		final String sessionId = getCookieValue(request);
		if(sessionId == null) {
			// Since there's no sessionId to use, just let the normal session stuff play out
			log.debug("No cookie for presisted session found");
			createCookie(request.getSession(true).getId(), request, response);
			requestForChain = request;
		} else {
			log.debug("Session cookie {} found: wrapping request with proxy session", sessionId);

			// Since we have a sessionId, we need to wrap the request to return the proxy session
			requestForChain = new HttpServletRequestWrapper(request) {

				private final SessionProxy session = proxySession(sessionId, request, response);

				/**
				* Provides the session. We don't bother checking the argument ({@code create}) because we know that we have
				* a session in existence as is.
				*/
				@Override
				public HttpSession getSession(boolean ignored) {
					return session;
				}

				@Override
				public HttpSession getSession() {
					return getSession(true);
				}
			};
		}

		stashSessionHash(requestForChain);

		log.debug("Passing off to the next filter in the chain: " + requestForChain + " " + chain);
		chain.doFilter(requestForChain, response);

		SessionHash original = readStashedSessionHash(requestForChain);

		try {
			// Persist the session only if there looks like there was a change
			if(original == null || !original.equals(new SessionHash(requestForChain.getSession()))) {
				persister.persistSession(SessionData.fromSession(requestForChain.getSession()));
			} else {
				log.debug("Not persisting session because there doesn't seem to have been a change");
			}
		} catch(IllegalStateException ise) {
			log.debug("Not persisting session because it seems to be invalid", ise);
		} catch(Exception e) {
			log.error("Unknown exception while persisting " + requestForChain.getSession().getId(), e);
		}
	}

	protected void stashSessionHash(HttpServletRequest request) {
		request.setAttribute(getClass().getName() + ".hash", new SessionHash(request.getSession()));
	}

	protected SessionHash readStashedSessionHash(HttpServletRequest request) {
		return (SessionHash)request.getAttribute(getClass() + ".hash");
	}

	protected SessionProxy proxySession(final String sessionId, final HttpServletRequest request,
			final HttpServletResponse response) {
		log.debug("Creating HttpSession proxy for request for {}", request.getRequestURL());
		return new SessionProxy(getServletContext(), persister, sessionId);
	}


	protected Cookie getCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (COOKIE_NAME.equals(cookie.getName())) {
					return cookie;
				}
			}
		}

		return null;
	}

	protected String getCookieValue(HttpServletRequest request) {
		Cookie cookie = getCookie(request);
		return cookie == null ? null : cookie.getValue();
	}

	protected void createCookie(String sessionId, HttpServletRequest request, HttpServletResponse response) {
		Cookie cookie = getCookie(request);
		if (cookie == null) {
			cookie = newCookie(sessionId, request);
			log.debug("Created new session cookie {}", sessionId);
		}
		else {
			log.debug("Updating existing cookie with id {} to new value {}", cookie.getValue(), sessionId);
			cookie.setValue(sessionId);
		}
		response.addCookie(cookie);
	}

	protected Cookie newCookie(String sessionId, HttpServletRequest request) {
		Cookie cookie = new Cookie(COOKIE_NAME, sessionId);
		//cookie.setDomain(request.getServerName()); // TODO needs config option
		cookie.setPath("/");
		cookie.setSecure(false); // TODO Should this be request.isSecure() or config option?
		cookie.setMaxAge(-1); // Discard at browser close // TODO needs config option
		return cookie;
	}

	protected void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
		Cookie cookie = getCookie(request);
		if (cookie == null) {
			return;
		}

		cookie = newCookie(cookie.getValue(), request);
		cookie.setMaxAge(0);
		response.addCookie(cookie);
		log.debug("Deleted cookie with id {}", cookie.getValue());
	}

	/**
	 * Dependency injection for the persister.
	 * @param persister the persister
	 */
	public void setPersister(Persister persister) {
		this.persister = persister;
	}

	protected Persister getPersister() {
		return persister;
	}

	@Override
	public void afterPropertiesSet() throws ServletException {
		super.afterPropertiesSet();
		Assert.notNull(persister, "persister must be specified");
	}
}
