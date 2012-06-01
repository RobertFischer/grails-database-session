package grails.plugin.databasesession;

/**
 * @author Burt Beckwith
 */
public class InvalidatedSessionException extends IllegalStateException {

	private static final long serialVersionUID = 2;

	/**
	 * Constructor.
	 */
	public InvalidatedSessionException(String message) {
		super(message);
	}

}
