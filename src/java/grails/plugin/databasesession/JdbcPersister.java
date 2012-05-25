package grails.plugin.databasesession;

import java.io.*;
import java.util.*;
import java.sql.*;

import java.security.MessageDigest;
import java.security.DigestOutputStream;

import org.apache.commons.io.IOUtils;

import org.apache.log4j.Logger;

import org.springframework.dao.*;
import org.springframework.jdbc.*;
import org.springframework.jdbc.core.*;



/**
 * Persists the session using JDBC. Note that this requires a table to be created: this can be done by calling
 * {@link #createTable()}, or by executing equivalent SQL yourself against the database.
 * 
 * @author Robert Fischer
 */
public class JdbcPersister implements Persister {

	private static final Logger log = Logger.getLogger(JdbcPersister.class);

	private volatile JdbcTemplate jdbcTemplate;
	public void setJdbcTemplate(JdbcTemplate template) {
		this.jdbcTemplate = template;
	}
	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	private volatile String tableName = "grailsSessionData";
	public void setTableName(String tableName) {
		this.tableName = tableName;
	}
	public String getTableName() {
		return tableName;
	}

	public volatile String nowFunc = "?";
	public String getCurrentTimestampDbFunction() {
		return nowFunc;
	}
	public void setCurrentTimestampDbFunction(String functionCall) {
		if(functionCall == null) {
			nowFunc = "?";
		} else {
			nowFunc = functionCall;
		}
	}

	private volatile int defaultMaxInactiveInterval = 600;
	public void setDefaultMaxInactiveInterval(int newDefault) {
		this.defaultMaxInactiveInterval = newDefault;
	}
	public int getDefaultMaxInactiveInterval() {
		return defaultMaxInactiveInterval;
	}

	public void init() {
		if(jdbcTemplate == null) {
			throw new IllegalStateException("jdbcTemplate property must be assigned (cannot be null)");
		}
		if(tableName == null) {
			throw new IllegalStateException("tableName property must be assigned (cannot be null)");
		}
		getMessageDigest(); // Make sure it works
		createTable();
	}

	public void createTable() {
		try {
			jdbcTemplate.execute(
				"CREATE TABLE " + getTableName() + " (\n" + 
					"sessionId VARCHAR(255) NOT NULL PRIMARY KEY,\n" +
					"sessionHash CHAR(" + (getMessageDigest().getDigestLength()/4) + ") NOT NULL,\n" + 
					"sessionData BLOB NOT NULL,\n" +
					"createdAt TIMESTAMP NOT NULL,\n"+
					"lastAccessedAt TIMESTAMP NOT NULL,\n"+
					"maxInactiveInterval INT NOT NULL\n"
				+")"
			);
		} catch(DataAccessException dae) {
			log.info(
				"Looks like the table is already created: " + dae.getClass().getSimpleName() + " " + dae.getMessage()
				+ "\n(If not, set the " + log.getName() + " logger to debug for the full stack trace.)"
			);
			log.debug("Exception encountered when attempting to create the table", dae);
		} 
	}

	private static MessageDigest getMessageDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch(java.security.NoSuchAlgorithmException nsae) {
			throw new RuntimeException("Could not find SHA-256 on your virtual machine, even though it is required to be there!", nsae);	
		}
	}

	private static final class SessionBytes {
		public final SessionData session;
		public final String hash;
		public final InputStream byteStream;

		public SessionBytes(SessionData session, byte[] hash, InputStream byteStream) {
			this.session = session;
			String hashStr = "";
			for(byte b : hash) hashStr = hashStr + Integer.toHexString(b);
			this.hash = hashStr;
			this.byteStream = byteStream;
		}
	}

	private static SessionBytes sessionToBytes(SessionData session) {
		try {	
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final DigestOutputStream dos = new DigestOutputStream(baos, getMessageDigest());
			final ObjectOutputStream oos = new ObjectOutputStream(dos);
			oos.writeObject(new HashMap<String,Serializable>(session.attrs));
			oos.close();
			return new SessionBytes(session, dos.getMessageDigest().digest(), new ByteArrayInputStream(baos.toByteArray()));
		} catch(java.io.IOException ioe) {
			throw new RuntimeException("IO Exception while converting the session to bytes: cannot serialize!", ioe);
		}
	}

	/**
	* Persists a session to the data store. The sessionData may be {@code null}.
	*/
	public void persistSession(SessionData session) {
		final SessionBytes data = sessionToBytes(session);
		if(session.isNew) {
			insertSession(data);
		} else {
			updateSession(data);
		}
	}

	private void insertSession(final SessionBytes data) {
		final String timestamp = getCurrentTimestampDbFunction();
		
		final List<Object> arguments = new ArrayList<Object>(6);
		arguments.add(data.session.sessionId);
		arguments.add(new SqlParameterValue(Types.BLOB, data.byteStream));
		arguments.add(data.hash);
		arguments.add(data.session.maxInactiveInterval);
		if("?".equals(timestamp)) {
			final java.util.Date now = new java.util.Date();
			arguments.add(now);
			arguments.add(now);
		}
	
		jdbcTemplate.update(
			"INSERT " + getTableName() + 
				" (sessionId, sessionData, sessionHash, maxInactiveInterval, createdAt    , lastAccessedAt) VALUES " +
				" (?        , ?          , ?          , ?                  , "+timestamp+","+timestamp+  ")",
			arguments.toArray(new Object[0])
		);
	}

	private void updateSession(SessionBytes data) {
		final List<Object> arguments = new ArrayList<Object>(6);
		arguments.add(new SqlParameterValue(Types.BLOB, data.byteStream));
		arguments.add(data.hash);
		arguments.add(new java.sql.Date(data.session.lastAccessedAt));
		arguments.add(new java.sql.Date(data.session.maxInactiveInterval));
		arguments.add(data.session.sessionId);
		arguments.add(data.hash);

		jdbcTemplate.update(
			"UPDATE " + getTableName() + 
				" SET sessionData = ?, sessionHash = ?, lastAccessedAt = ?, maxInactiveInterval = ? " + 
				" WHERE sessionId = ? AND sessionHash <> ?",
			arguments.toArray(new Object[0])
		);
	}

	/**
	* Retrieves the session data for the given session. May be {@code null}.
	*/
	public SessionData getSessionData(final String sessionId) {
		log.debug("Getting session data for " + sessionId);
		try {
			return jdbcTemplate.queryForObject(
				"SELECT sessionId, sessionData, createdAt, lastAccessedAt, maxInactiveInterval " + 
					"FROM " + getTableName() + " WHERE sessionId = ?",
				new Object[] { sessionId },
				new RowMapper<SessionData>() {
					public SessionData mapRow(ResultSet rs, int rowNum) throws SQLException {
						return new SessionData(
							rs.getString(1),
							readAttributes(rs.getBinaryStream(2)),
							rs.getDate(3).getTime(),
							rs.getDate(4).getTime(),
							rs.getInt(5), false
						);
					}
				}
			);
		} catch(IncorrectResultSizeDataAccessException e) {
			if(e.getActualSize() == 0) {
				log.debug("Could not find any session data for " + sessionId);
				return null;
			}
			log.warn("More than one record with session id " + sessionId, e);
			throw e;
		}
	}

	private static Map<String,Serializable> readAttributes(InputStream is) {
		if(is == null) {
			log.warn("Asked to read from a null attributes stream");
			return Collections.emptyMap();
		}
		try {
			return (Map<String,Serializable>)(new ObjectInputStream(new BufferedInputStream(is)).readObject());
		} catch(java.lang.ClassNotFoundException cnfe) {
			throw new RuntimeException("Could not find the class to deserialize the session", cnfe);
		} catch(java.io.IOException ioe) {
			throw new RuntimeException("I/O Exception while reading session from database", ioe);
		} finally {
			IOUtils.closeQuietly(is);
		}
	}

	/**
	 * Delete a session and its attributes.
	 * @param sessionId the session id
	 */
	public void invalidate(String sessionId) {
		jdbcTemplate.update("DELETE " + getTableName() + " WHERE sessionId = ?", sessionId);
	}

	/**
	 * Check if the session is valid.
	 * @param sessionId the session id
	 * @return true if the session exists and hasn't been invalidated
	 */
	public boolean isValid(String sessionId) {
		return 1 == jdbcTemplate.queryForInt("SELECT COUNT(*) FROM " + getTableName() + " WHERE sessionId = ?", sessionId);
	}

  /**
  * Provides the valid session ids that are stored within this persister.
  */
  public Iterator<String> getSessionIds() {
		List<String> ids = jdbcTemplate.queryForList("SELECT sessionId FROM " + getTableName(), String.class, new Object[0]);
		return ids.iterator();
	}

}
