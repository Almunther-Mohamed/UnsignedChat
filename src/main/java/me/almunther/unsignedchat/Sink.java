package me.almunther.unsignedchat;

/** Tiny logging abstraction so the shared code does not care which proxy it runs on. */
public interface Sink {
    void info(String message);

    void warn(String message);

    /** Only printed when {@code debug=true} in config.properties. */
    void debug(String message);
}
