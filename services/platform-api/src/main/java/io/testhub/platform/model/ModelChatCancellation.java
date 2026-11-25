package io.testhub.platform.model;

import java.util.concurrent.CancellationException;
import okhttp3.Call;

public final class ModelChatCancellation {
    private volatile boolean cancelled;
    private volatile Call call;

    public void cancel() {
        cancelled = true;
        Call active = call;
        if (active != null) active.cancel();
    }

    void bind(Call active) {
        call = active;
        if (cancelled && active != null) active.cancel();
    }

    public void check() {
        if (cancelled) throw new CancellationException();
    }
}
