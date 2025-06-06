package alg.raft.utils;

public final class Magics {

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_RPC_PORT = 9090;

    public static final int DEFAULT_HTTP_CLIENT_IDLE_TIMEOUT_SECONDS = 180;
    public static final int DEFAULT_HTTP_CLIENT_KEEP_ALIVE_TIME_SECONDS = 180;
    public static final int DEFAULT_HEARTBEAT_RPC_SYNC_TIMEOUT_MILLIS = 100;
    public static final int DEFAULT_VOTE_RPC_SYNC_TIMEOUT_MILLIS = 500;
    public static final int MAX_APPEND_ENTRIES_RPC_SYNC_TIMEOUT_MILLIS = 500;
    public static final int MAX_APPEND_ENTRIES_RPC_ASYNC_TIMEOUT_MILLIS = 600;
}
