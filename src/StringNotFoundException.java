
public class StringNotFoundException extends Exception {
	private static final long serialVersionUID = 1L;
	public StringNotFoundException() { super(); }
	public StringNotFoundException(String message) { super(message); }
	public StringNotFoundException(String message, Throwable cause) { super(message, cause); }
	public StringNotFoundException(Throwable cause) { super(cause); }
}