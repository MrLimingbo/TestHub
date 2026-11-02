package io.testhub.platform.shared.api;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

public final class DependencyFailures {
    private DependencyFailures() {}

    public static boolean isUnavailable(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean redisOperation = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            redisOperation |= cause instanceof org.springframework.data.redis.RedisSystemException
                    || cause instanceof io.lettuce.core.RedisException;
            if (cause instanceof DataAccessResourceFailureException || cause instanceof QueryTimeoutException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof io.lettuce.core.RedisConnectionException
                    || cause instanceof io.lettuce.core.RedisCommandTimeoutException) return true;
            // Lettuce can wrap a transport reset in a generic RedisException, rather than RedisConnectionException.
            // A socket failure outside a Redis operation is not automatically a control-plane dependency outage.
            if (redisOperation && (cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.nio.channels.ClosedChannelException || cause instanceof java.util.concurrent.TimeoutException)) return true;
        }
        return false;
    }
}
