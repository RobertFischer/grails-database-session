package grails.plugin.databasesession

/**
 * @author Robert Fischer
 */
class DatabaseCleanupService {

	def persisterJdbcTemplate
	def jdbcPersister

	/**
	 * Delete PersistentSessions and corresponding PersistentSessionAttributes where
	 * the last accessed time is older than a cutoff value.
	 */
	void cleanup() {
		if(jdbcPersister) {

			log.info("Cleaning up old database-persisted sessions");

			int cleanedUpRows = persisterJdbcTemplate.update("""
				DELETE FROM ${jdbcPersister.tableName} WHERE lastAccessedAt < 
			""", (new Date()-1))

			log.info("Done cleaning up: cleaned up $cleanedUpRows rows")
		}

	}

}
