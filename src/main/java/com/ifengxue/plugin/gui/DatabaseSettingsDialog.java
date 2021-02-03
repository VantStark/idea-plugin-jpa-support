package com.ifengxue.plugin.gui;

import com.ifengxue.plugin.Constants;
import com.ifengxue.plugin.Holder;
import com.ifengxue.plugin.action.JpaSupport;
import com.ifengxue.plugin.adapter.DriverDelegate;
import com.ifengxue.plugin.component.DatabaseSettings;
import com.ifengxue.plugin.entity.MybatisGeneratorTableSchema;
import com.ifengxue.plugin.entity.TableSchema;
import com.ifengxue.plugin.i18n.LocaleContextHolder;
import com.ifengxue.plugin.i18n.LocaleItem;
import com.ifengxue.plugin.state.DatabaseSettingsState;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications.Bus;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import fastjdbc.FastJdbc;
import fastjdbc.NoPoolDataSource;
import fastjdbc.SimpleFastJdbc;
import java.awt.event.ItemEvent;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import javax.swing.Action;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.ModelType;
import org.mybatis.generator.config.TableConfiguration;
import org.mybatis.generator.internal.db.DatabaseIntrospector;
import org.mybatis.generator.internal.types.JavaTypeResolverDefaultImpl;

public class DatabaseSettingsDialog extends DialogWrapper {

  public static AtomicReference<ClassLoader> classLoaderRef = new AtomicReference<>(JpaSupport.class.getClassLoader());
  private final Logger log = Logger.getInstance(DatabaseSettingsDialog.class);
  private final DatabaseSettings databaseSettings;
  private final Project project;

  protected DatabaseSettingsDialog(Project project) {
    super(project, true);
    this.project = project;
    databaseSettings = new DatabaseSettings();
    init();
    setTitle(LocaleContextHolder.format("set_up_database_connection"));

    // init text field
    initTextField(databaseSettings);

    // register language change listener
    databaseSettings.getCbxSelectLanguage().addItemListener(itemEvent -> {
      if (itemEvent.getStateChange() != ItemEvent.SELECTED) {
        return;
      }
      LocaleContextHolder.setCurrentLocale(((LocaleItem) itemEvent.getItem()).getLocale());
      databaseSettings.getData(ServiceManager.getService(project, DatabaseSettingsState.class));
    });
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    FileChooserDescriptor descriptor = new FileChooserDescriptor(false, false, true, true, false, false);
    descriptor.withShowHiddenFiles(true);
    try {
      Path m2Path = Paths.get(System.getProperty("user.home"), ".m2", "repository");
      VirtualFile root = LocalFileSystem.getInstance().findFileByIoFile(m2Path.toFile());
      if (root != null) {
        descriptor.withRoots(root);
      }
    } catch (Exception ignored) {
    }
    databaseSettings.getTextDriverPath()
        .addBrowseFolderListener("Choose Database Driver", "Choose database driver", project, descriptor);
    return databaseSettings.getRootComponent();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{getOKAction(), getCancelAction()};
  }

  @Nullable
  @Override
  protected ValidationInfo doValidate() {
    if (databaseSettings.getTextHost().getText().trim().isEmpty()) {
      return new ValidationInfo("Must set host", databaseSettings.getTextHost());
    }
    if (databaseSettings.getTextPort().getText().trim().isEmpty()) {
      return new ValidationInfo("Must set port", databaseSettings.getTextHost());
    }
    if (databaseSettings.getTextUsername().getText().trim().isEmpty()) {
      return new ValidationInfo("Must set username", databaseSettings.getTextUsername());
    }
    if (databaseSettings.getTextDatabase().getText().trim().isEmpty()) {
      return new ValidationInfo("Must set database", databaseSettings.getTextDatabase());
    }
    if (databaseSettings.getTextConnectionUrl().getText().trim().isEmpty()) {
      return new ValidationInfo("Must set connection url", databaseSettings.getTextConnectionUrl());
    }
    String filename = databaseSettings.getTextDriverPath().getText().trim();
    if (filename.isEmpty()) {
      return new ValidationInfo("Must set database driver path", databaseSettings.getTextDriverPath());
    }
    VirtualFile driverFile = LocalFileSystem.getInstance().findFileByIoFile(Paths.get(filename).toFile());
    if (driverFile == null || !driverFile.exists()) {
      return new ValidationInfo("Invalid database driver path", databaseSettings.getTextDriverPath());
    }
    if (databaseSettings.getTextDriverClass().getText().trim().isEmpty()) {
      return new ValidationInfo("Invalid database driver path", databaseSettings.getTextDriverClass());
    }
    return super.doValidate();
  }

  @Override
  protected void doOKAction() {
    String host = databaseSettings.getTextHost().getText().trim();
    String port = databaseSettings.getTextPort().getText().trim();
    String username = databaseSettings.getTextUsername().getText().trim();
    String password = new String(databaseSettings.getTextPassword().getPassword()).trim();
    String database = databaseSettings.getTextDatabase().getText().trim();
    String connectionUrl = databaseSettings.getTextConnectionUrl().getText().trim();
    String driverPath = databaseSettings.getTextDriverPath().getText().trim();
    String driverClass = databaseSettings.getTextDriverClass().getText().trim();
    saveTextField(host, port, username, password);

    unloadDrivers();
    WriteCommandAction.runWriteCommandAction(project, () -> {
      try {
        try {
          classLoaderRef.set(new URLClassLoader(
              new URL[]{new File(driverPath).toURI().toURL()}, getClass().getClassLoader()));
          Class.forName(driverClass, true, classLoaderRef.get());
        } catch (MalformedURLException | ClassNotFoundException ex) {
          ApplicationManager.getApplication().invokeLater(() -> Messages.showOkCancelDialog(project,
              LocaleContextHolder.format("driver_not_found", Holder.getDatabaseDrivers().getDriverClass()),
              LocaleContextHolder.format("prompt"),
              Messages.OK_BUTTON,
              Messages.CANCEL_BUTTON,
              Messages.getQuestionIcon()));
          return;
        }
        Driver driver = (Driver) Class
            .forName(driverClass, true, classLoaderRef.get())
            .getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverDelegate(driver, driverPath));
      } catch (ReflectiveOperationException | SQLException e1) {
        ApplicationManager.getApplication().invokeLater(() -> Bus
            .notify(new Notification(Constants.GROUP_ID, "Error",
                LocaleContextHolder.format("database_not_exists", Holder.getDatabaseDrivers().getDriverClass()),
                NotificationType.ERROR)));
        return;
      }
      // 尝试获取连接
      try (Connection ignored = DriverManager.getConnection(connectionUrl, username, password)) {
        FastJdbc fastJdbc = new SimpleFastJdbc(new NoPoolDataSource(connectionUrl, username, password));
        Holder.registerFastJdbc(fastJdbc);
      } catch (SQLException se) {
        ApplicationManager.getApplication().invokeLater(() -> {
          Bus.notify(new Notification(Constants.GROUP_ID, "Error",
              LocaleContextHolder.format("connect_to_database_failed",
                  se.getErrorCode(), se.getSQLState(), se.getLocalizedMessage()), NotificationType.ERROR));
          Messages.showErrorDialog(LocaleContextHolder.format("connect_to_database_failed",
              se.getErrorCode(), se.getSQLState(), se.getLocalizedMessage()), Constants.NAME);
        });
        log.warn("connect to database failed", se);
        return;
      }

      try {
        List<TableSchema> tableSchemaList = findDatabaseSchemas(database);
        ApplicationManager.getApplication().invokeLater(() -> {
          dispose();
          // show dialog
          AutoGeneratorSettingsDialog
              .show(tableSchemaList, tableSchema -> ((MybatisGeneratorTableSchema) tableSchema).toColumnSchemas());
        });
      } catch (SQLException se) {
        String sb = "SQL error code: " + se.getErrorCode()
            + "\nSQL error state: " + se.getSQLState() + "\n"
            + "Error message: " + se.getLocalizedMessage();
        ApplicationManager.getApplication()
            .invokeLater(() -> Bus.notify(new Notification(Constants.GROUP_ID, "Error",
                sb, NotificationType.ERROR)));
      }
    });
  }

  private void unloadDrivers() {
    List<DriverDelegate> driverDelegates = new ArrayList<>();
    Enumeration<Driver> enumeration = DriverManager.getDrivers();
    while (enumeration.hasMoreElements()) {
      Driver driver = enumeration.nextElement();
      if (driver instanceof DriverDelegate) {
        driverDelegates.add((DriverDelegate) driver);
      }
    }
    driverDelegates.forEach(driverDelegate -> {
      try {
        DriverManager.deregisterDriver(driverDelegate);
      } catch (SQLException ignored) {
      }
    });
    classLoaderRef.set(null);
  }

  private List<TableSchema> findDatabaseSchemas(String database) throws SQLException {
    DataSource datasource = ((SimpleFastJdbc) Holder.getFastJdbc()).getDatasource();
    try (Connection connection = datasource.getConnection()) {
      List<String> warnings = new ArrayList<>();
      Context context = new Context(ModelType.FLAT);
      DatabaseIntrospector introspector = new DatabaseIntrospector(
          context, connection.getMetaData(), new JavaTypeResolverDefaultImpl(), warnings);
      TableConfiguration tc = new TableConfiguration(context);
      tc.setCatalog(database);
      return introspector.introspectTables(tc)
          .stream()
          .map(MybatisGeneratorTableSchema::new)
          .collect(Collectors.toList());
    }
  }

  @Nullable
  @Override
  protected String getDimensionServiceKey() {
    return Constants.NAME + ":" + getClass().getName();
  }

  public static void showDialog() {
    new DatabaseSettingsDialog(Holder.getProject()).show();
  }

  private void saveTextField(String host, String port, String username, String password) {
    DatabaseSettingsState databaseSettingsState = ServiceManager.getService(project, DatabaseSettingsState.class);
    databaseSettings.getData(databaseSettingsState);
    // save password if needed
    if (databaseSettingsState.isRequireSavePassword()) {
      CredentialAttributes credentialAttributes = createCredentialAttributes(host, port, username);
      Credentials credentials = new Credentials(username, password);
      PasswordSafe.getInstance().set(credentialAttributes, credentials);
    }
  }

  @NotNull
  private CredentialAttributes createCredentialAttributes(String host, String port, String username) {
    return new CredentialAttributes("JpaSupport://" + host + ":" + port,
        username, getClass(), false);
  }

  private void initTextField(DatabaseSettings databaseSettings) {
    setOKButtonText(LocaleContextHolder.format("button_next_step"));
    setCancelButtonText(LocaleContextHolder.format("button_cancel"));

    DatabaseSettingsState databaseSettingsState = ServiceManager.getService(project, DatabaseSettingsState.class);
    databaseSettings.setData(databaseSettingsState);

    if (databaseSettingsState.isRequireSavePassword()) {
      // load password
      CredentialAttributes credentialAttributes = createCredentialAttributes(databaseSettings.getTextHost().getText(),
          databaseSettings.getTextPort().getText(), databaseSettings.getTextUsername().getText());
      Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes);
      String password = "";
      if (credentials != null) {
        password = credentials.getPasswordAsString();
      }
      databaseSettings.getTextPassword().setText(password);
    }
  }
}
