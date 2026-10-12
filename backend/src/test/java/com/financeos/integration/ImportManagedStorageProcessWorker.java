package com.financeos.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.financeos.module.importing.mapper.*;
import com.financeos.module.importing.service.TransactionImportFileLifecycleService;
import com.financeos.module.importing.storage.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** Independent process using the production Storage/Registry/transactions, only synthetic test DB credentials. */
public final class ImportManagedStorageProcessWorker {
    public static void main(String[] arguments) {
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(required("C9_DB_URL"), required("C9_DB_USER"), required("C9_DB_PASSWORD"));
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
            configuration.addMapper(TransactionImportFileObjectMapper.class);
            configuration.addMapper(TransactionImportSessionMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
            var lifecycle = new TransactionImportFileLifecycleService(sql.getMapper(TransactionImportFileObjectMapper.class),
                    sql.getMapper(TransactionImportSessionMapper.class), new DataSourceTransactionManager(source));
            UUID deployment = UUID.fromString(required("C9_DEPLOYMENT"));
            UUID storage = UUID.fromString(required("C9_STORAGE"));
            Path root = Path.of(required("C9_ROOT"));
            if ("PROVISION".equals(arguments[0])) TrustedImportStorageRoot.provision(root, deployment, storage, "RAW");
            try (var local = new LocalTemporaryImportFileStorage(root, 5 * 1024 * 1024, deployment, storage, "RAW", lifecycle)) {
                if ("WRITE".equals(arguments[0])) {
                    InputStream content = new InputStream() {
                        private boolean first = true;
                        @Override public int read() throws IOException { throw new IOException("Use bounded block read"); }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            if (first) {
                                first = false;
                                byte[] synthetic = "synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                System.arraycopy(synthetic, 0, bytes, offset, synthetic.length);
                                return synthetic.length;
                            }
                            signal("WRITING");
                            // The real production save worker holds both its open part and OS guard here.
                            if (!"RELEASE".equals(new BufferedReader(new InputStreamReader(System.in)).readLine())) {
                                // Containers normally have no stdin. Keep actual IO in progress until the test kills this process.
                                try { Thread.sleep(120000); } catch (InterruptedException exception) { throw new IOException("Worker interrupted"); }
                            }
                            return -1;
                        }
                    };
                    local.save(1L, UUID.fromString(required("C9_SESSION")), content, "synthetic.csv");
                    signal("SAVED");
                } else if ("CLEANUP".equals(arguments[0])) {
                    local.cleanupRetired();
                    signal("CLEANED");
                } else if ("PROVISION".equals(arguments[0])) signal("PROVISIONED");
                else throw new IllegalArgumentException();
            }
        } catch (Throwable failure) {
            signal("C9_WORKER_FAILURE");
            System.exit(2);
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static void signal(String value) { System.out.println(value); System.out.flush(); }
}
