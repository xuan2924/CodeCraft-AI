package ai.codecraft.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.List;

/** Counts the stream without retaining a second body copy. One large synchronous
 * input may be processed at a time; this is runtime memory admission, not data quota.
 */
public final class RequestBodyAdmission {
    public static final long SMALL_JSON_BYTES = 16 * 1024;
    public static final long LARGE_JSON_BYTES = 64L * 1024 * 1024;
    private final Semaphore large = new Semaphore(1);
    private final List<RequestBodyPolicy> policies;

    public RequestBodyAdmission(List<RequestBodyPolicy> policies) { this.policies = List.copyOf(policies); }

    public long maximumBytes(HttpServletRequest request) {
        return Math.max(SMALL_JSON_BYTES, policies.stream().mapToLong(policy -> policy.maximumBytes(request)).max().orElse(0));
    }

    public Admission admit(HttpServletRequest request, long maximum) {
        if (maximum < 1 || maximum > LARGE_JSON_BYTES) throw new IllegalArgumentException("Invalid request byte limit");
        long length = request.getContentLengthLong();
        if (length > maximum) throw new RequestBodyLimitException(RequestBodyErrorCode.REQUEST_TOO_LARGE);
        boolean needsPermit = maximum > SMALL_JSON_BYTES && (length < 0 || length > SMALL_JSON_BYTES);
        if (needsPermit && !large.tryAcquire()) throw new RequestBodyLimitException(RequestBodyErrorCode.REQUEST_CAPACITY);
        return new Admission(new LimitedRequest(request, maximum), needsPermit ? large : null);
    }

    public static final class Admission implements AutoCloseable {
        private final HttpServletRequest request;
        private final Semaphore permit;
        private boolean released;
        private Admission(HttpServletRequest request, Semaphore permit) { this.request = request; this.permit = permit; }
        public HttpServletRequest request() { return request; }
        @Override public void close() { if (!released && permit != null) permit.release(); released = true; }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximum;
        private ServletInputStream stream;
        LimitedRequest(HttpServletRequest request, long maximum) { super(request); this.maximum = maximum; }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedStream(super.getInputStream(), maximum);
            return stream;
        }
        @Override public BufferedReader getReader() throws IOException {
            Charset encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream source;
        private final long maximum;
        private long consumed;
        LimitedStream(ServletInputStream source, long maximum) { this.source = source; this.maximum = maximum; }
        @Override public int read() throws IOException {
            int value = source.read();
            if (value >= 0) count(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            if (read > 0) count(read);
            return read;
        }
        private void count(int count) {
            consumed += count;
            if (consumed > maximum) throw new RequestBodyLimitException(RequestBodyErrorCode.REQUEST_TOO_LARGE);
        }
        @Override public boolean isFinished() { return source.isFinished(); }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
        @Override public void close() throws IOException { source.close(); }
    }
}
