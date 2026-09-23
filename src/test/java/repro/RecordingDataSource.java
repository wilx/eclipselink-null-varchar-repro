package repro;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Records JDBC calls while passing every operation through to the real driver. */
final class RecordingDataSource implements DataSource {
    private final DataSource delegate;
    final List<String> executions = new ArrayList<>();

    RecordingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(method, connection, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                        return wrap(statement, (String) args[0]);
                    }
                    return result;
                });
    }

    private PreparedStatement wrap(PreparedStatement statement, String sql) {
        Map<Integer, String> bindings = new TreeMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("clearParameters")) {
                        bindings.clear();
                    } else if (name.startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer index) {
                        if (name.equals("setNull")) {
                            int type = (Integer) args[1];
                            bindings.put(index, "setNull(" + JDBCType.valueOf(type) + "," + type + ")");
                        } else {
                            bindings.put(index, name + "(" + args[1] + ")");
                        }
                    }
                    if (name.startsWith("execute")) {
                        String execution = sql + " | " + bindings;
                        executions.add(execution);
                        System.out.println("[JDBC] " + execution);
                    }
                    return invoke(method, statement, args);
                });
    }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("repro"); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
