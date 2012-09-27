package grails.plugin.databasesession;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.sql.*;

import java.security.MessageDigest;
import java.security.DigestOutputStream;

import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;

import org.apache.commons.io.IOUtils;

import org.apache.log4j.Logger;

import org.springframework.dao.*;
import org.springframework.jdbc.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import org.springframework.beans.factory.InitializingBean;


/**
 * Persists the session using JDBC. Note that this requires a table to be created: this can be done by calling
 * {@link #createTable()}, or by executing equivalent SQL yourself against the database.
 * 
 * @author Robert Fischer
 */
public class JdbcPersister implements Persister, InitializingBean {

	private static final Logger log = Logger.getLogger(JdbcPersister.class);

	private volatile JdbcTemplate jdbcTemplate;
	public void setJdbcTemplate(JdbcTemplate template) {
		this.jdbcTemplate = template;
	}
	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	private volatile TransactionTemplate transactionTemplate;
	public void setTransactionTemplate(TransactionTemplate template) {
		this.transactionTemplate = template;
	}
	public TransactionTemplate getTransactionTemplate() {
		return transactionTemplate;
	}

	private volatile String tableName = "grailsSessionData";
	public void setTableName(String tableName) {
		this.tableName = tableName;
	}
	public String getTableName() {
		return tableName;
	}

	private volatile String nowFunc = "?";
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

	// TODO We should be able to look this up somehow based on the BLOB type.
	private volatile String binaryType = "BLOB";
    private volatile int sqlBinaryType = Types.BLOB;

    public int getSqlBinaryType() {
        return sqlBinaryType;
    }

    public void setSqlBinaryType(int sqlBinaryType) {
        this.sqlBinaryType = sqlBinaryType;
    }

	public String getBinaryType() {
		return binaryType;
	}
	public void setBinaryType(String binaryTypeName) {
		if(binaryTypeName == null) {
			this.binaryType = "BLOB";
		} else {
			this.binaryType = binaryTypeName;
		}
	}

	public void afterPropertiesSet() {
		if(jdbcTemplate == null) {
			throw new IllegalStateException("jdbcTemplate property must be assigned (cannot be null)");
		}
		if(transactionTemplate == null) {
			throw new IllegalStateException("transactionTemplate property must be assigned (cannot be null)");
		}
		if(tableName == null) {
			throw new IllegalStateException("tableName property must be assigned (cannot be null)");
		}
		log.debug("Transaction template configuration: " + 
			transactionTemplate.getIsolationLevel() + " - " + transactionTemplate.getPropagationBehavior()
		);
		getMessageDigest(); // Make sure it works
		createTable();
	}

	public void createTable() {
		log.debug("Seeing if we are creating a table");
		try {
			jdbcTemplate.execute(
				"CREATE TABLE IF NOT EXISTS " + getTableName() + " (\n" +
					"sessionId VARCHAR(255) NOT NULL PRIMARY KEY,\n" +
					"sessionHash CHAR(64) NOT NULL,\n" + 
					"sessionData " + getBinaryType() + " NOT NULL,\n" +
					"createdAt TIMESTAMP NOT NULL,\n"+
					"lastAccessedAt TIMESTAMP NOT NULL,\n"+
					"maxInactiveInterval INT NOT NULL\n"
				+")"
			);
			log.info("If not already present, created the table for sessions: " + getTableName());
		} catch(Exception e) {
			log.warn("Unknown error while creating the table for sessions", e);
		}
	}

	private static final String algorithm = "SHA-256";
	private static MessageDigest getMessageDigest() {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch(java.security.NoSuchAlgorithmException nsae) {
			throw new RuntimeException("Could not find " + algorithm + " on your virtual machine", nsae);	
		}
	}

	private static final class SessionBytes {
		public final SessionData session;
		public final String hash;
		public final byte[] bytes;

		public SessionBytes(final SessionData session, final byte[] hashBytes, final byte[] dataBytes) {
			this.session = session;
			this.bytes = dataBytes;

			String hashStr = "";
			for(byte b : hashBytes) hashStr = hashStr + String.format("%02X", (int)b & 0xff);
			this.hash = hashStr;
		}
	}

	private static SessionBytes sessionToBytes(SessionData session) {
		try {	
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final DigestOutputStream dos = new DigestOutputStream(baos, getMessageDigest());
			final ObjectOutputStream oos = new ObjectOutputStream(dos);
			oos.writeObject(new HashMap<String,Serializable>(session.attrs));
			oos.close();
			return new SessionBytes(session, dos.getMessageDigest().digest(), baos.toByteArray());
		} catch(java.io.IOException ioe) {
			throw new RuntimeException("IO Exception while converting the session to bytes: cannot serialize!", ioe);
		}
	}

	/**
	* Persists a session to the data store. The sessionData may be {@code null}.
	*/
	@Override
	public void persistSession(SessionData session) {
		log.debug("Persisting session: " + session);
		final SessionBytes data = sessionToBytes(session);
		if(isValid(session.sessionId)) {
			updateSession(data);
		} else {
			insertSession(data);
		}
	}

	private void insertSession(final SessionBytes data) {
		final String timestamp = getCurrentTimestampDbFunction();
		
		final List<Object> arguments = new ArrayList<Object>(6);
		arguments.add(data.session.sessionId);
		arguments.add(new SqlParameterValue(getSqlBinaryType(), data.bytes));
		arguments.add(data.hash);
		arguments.add(data.session.maxInactiveInterval);
		if("?".equals(timestamp)) {
			final java.util.Date now = new java.util.Date();
			arguments.add(now);
			arguments.add(now);
		}

		transactionTemplate.execute(
			new TransactionCallback<Void>() {
				public Void doInTransaction(TransactionStatus status) {
					try {	
						jdbcTemplate.update(
							"INSERT INTO " + getTableName() + 
								" (sessionId, sessionData, sessionHash, maxInactiveInterval, createdAt    , lastAccessedAt) VALUES " +
								" (?        , ?          , ?          , ?                  , "+timestamp+","+timestamp+  ")",
							arguments.toArray(new Object[0])
						);
						status.flush();
						log.debug("Successfully inserted session: " + data.session.sessionId);
					} catch(DuplicateKeyException dke) {
						// Someone else did an insert at the same time!
						log.debug("Detected a duplicate key: " + data.session.sessionId + " (going to try for an update)");
						updateSession(data);
					} catch(Exception e) {
						log.error("Error persisting session: " + data.session.sessionId, e);
					}
					return null;
				}
			}
		);
	}

	private void updateSession(final SessionBytes data) {
		final List<Object> arguments = new ArrayList<Object>(6);
		arguments.add(new SqlParameterValue(getSqlBinaryType(), data.bytes));
		arguments.add(data.hash);
		arguments.add(new java.sql.Date(data.session.lastAccessedAt));
		arguments.add(data.session.maxInactiveInterval);
		arguments.add(data.session.sessionId);
		//arguments.add(data.hash);

		transactionTemplate.execute(
			new TransactionCallback<Void>() {
				public Void doInTransaction(TransactionStatus status) {
					try{ 
						int updatedRecords = jdbcTemplate.update(
							"UPDATE " + getTableName() + 
								" SET sessionData = ?, sessionHash = ?, lastAccessedAt = ?, maxInactiveInterval = ? " + 
								" WHERE sessionId = ? ", //AND sessionHash <> ?",
							arguments.toArray(new Object[0])
						);
						status.flush();
						if(updatedRecords == 0) {
							log.debug("Session was not updated, no records found: " + data.session.sessionId);
							insertSession(data);
						} else {
							log.debug("Updated session: " + data.session.sessionId);
						}
					} catch(Exception e) {
						log.error("Error updating session: " + data.session.sessionId, e);
					}
					return null;
				}
			}
		);
	} 

	/**
	* Retrieves the session data for the given session. May be {@code null}.
	*/
	public SessionData getSessionData(final String sessionId) {
		log.debug("Getting session data for " + sessionId);
		return 
			transactionTemplate.execute(new TransactionCallback<SessionData>() {
					public SessionData doInTransaction(TransactionStatus status) {
						try {
							return jdbcTemplate.queryForObject(
								"SELECT sessionId, sessionData, createdAt, lastAccessedAt, maxInactiveInterval " + 
									"FROM " + getTableName() + " WHERE sessionId = ?",
								new Object[] { sessionId },
								new RowMapper<SessionData>() {
									public SessionData mapRow(ResultSet rs, int rowNum) throws SQLException {
										log.debug("Processing session data row #" + rowNum + " for " + sessionId);
										try {
											return new SessionData(
												rs.getString(1),
												readAttributes(rs.getBytes(2)),
												rs.getDate(3).getTime(),
												rs.getDate(4).getTime(),
												rs.getInt(5)
											);
										} catch(SQLException sqle) {
											throw sqle;
										} catch(RuntimeException re) {
											log.error("Error processing row " + rowNum + " when fetching session data", re);
											throw re;
										} finally {
											log.debug("Done processing session data row #" + rowNum + " for " + sessionId);
										}
									}
								}
							);
						} catch(IncorrectResultSizeDataAccessException e) {
							if(e.getActualSize() == 0) {
								log.error("No database session data found: no records in the database for  " + sessionId);
								return null;
							}
							log.error("More than one record with session id " + sessionId, e);
							throw e;
						} catch(RuntimeException e) {
							log.error("Unhandled error while getting session data for " + sessionId, e);
							throw e;
						}
					}
		});
	}

	private static Map<String,Serializable> readAttributes(byte[] bytes) {
		if(bytes == null || bytes.length == 0) {
			log.warn("Asked to read from a null/empty attributes stream: " + Arrays.toString(bytes));
			return Collections.emptyMap();
		}
		try {
			return (Map<String,Serializable>)(new ObjectInputStream(new ByteArrayInputStream(bytes)).readObject());
		} catch(java.lang.ClassNotFoundException cnfe) {
			throw new RuntimeException("Could not find the class to deserialize the session", cnfe);
		} catch(java.io.IOException ioe) {
			throw new RuntimeException("I/O Exception while reading session from database", ioe);
		} 
	}

	/**
	 * Delete a session and its attributes.
	 * @param sessionId the session id
	 */
	@Override
	public void invalidate(String sessionId) {
		log.debug("Deleting the session " + sessionId);
		int rows = jdbcTemplate.update("DELETE FROM " + getTableName() + " WHERE sessionId = ?", sessionId);
		if(rows == 0) {
			log.debug("No session with id " + sessionId + " found in the database to invalidate");	
		} else {
			log.debug("Successfully deleted the session " + sessionId);
		}
	}

	/**
	 * Check if the session is valid.
	 * @param sessionId the session id
	 * @return true if the session exists and hasn't been invalidated
	 */
	@Override
	public boolean isValid(String sessionId) {
		return 1 == jdbcTemplate.queryForInt("SELECT COUNT(*) FROM " + getTableName() + " WHERE sessionId = ?", sessionId);
	}

	@Override
	public void cleanUp() {
		log.info("Executing database session cleanUp");

		// Date arithmetic is notoriously non-standard in SQL

		final long now = System.currentTimeMillis();
		final Object[][] toDelete = 
			Collections2.filter(
				jdbcTemplate.query(
					"SELECT sessionId, lastAccessedAt, maxInactiveInterval FROM " + getTableName(),
					new RowMapper<Object[]>() {
						public Object[] mapRow(ResultSet rs, int rowNum) throws SQLException {
							final String sessionId = rs.getString(1);
							final java.sql.Date lastAccessed = rs.getDate(2);
							final int maxInactiveSeconds = rs.getInt(3);
							if(lastAccessed.getTime() + TimeUnit.SECONDS.toMillis(maxInactiveSeconds) < now) {
								return new Object[] { sessionId, lastAccessed };
							} else {
								return null;
							}
						}
					}
				),
				Predicates.notNull()
			).toArray(new Object[0][0]);

		if(toDelete.length == 0) return;

		// Now do the big update: will automatically fall back to individual queries if need be
		jdbcTemplate.batchUpdate(
			// Check the lastAccessedAt to make sure we don't delete something which is suddenly used
			"DELETE FROM " + getTableName() + " WHERE sessionId = ? AND lastAccessedAt = ?",
			new BatchPreparedStatementSetter() {
				public void setValues(PreparedStatement ps, int i) throws SQLException {
					final Object[] args = toDelete[i];
					ps.setString(1, (String)args[0]);
					ps.setDate(2, (java.sql.Date)args[1]);
				}
		
				public int getBatchSize() {
					return toDelete.length;
				}
			}
		);

	}


}
