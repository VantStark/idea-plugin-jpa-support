package com.ifengxue.plugin.action;

import static java.util.stream.Collectors.toList;

import com.ifengxue.plugin.Holder;
import com.ifengxue.plugin.adapter.DatabaseDrivers;
import com.ifengxue.plugin.entity.ColumnSchema;
import com.ifengxue.plugin.entity.DatabasePluginColumnSchema;
import com.ifengxue.plugin.entity.DatabasePluginTableSchema;
import com.ifengxue.plugin.entity.TableSchema;
import com.ifengxue.plugin.gui.AutoGeneratorSettingsDialog;
import com.ifengxue.plugin.i18n.LocaleContextHolder;
import com.ifengxue.plugin.util.DatabasePluginUtil;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbTable;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.ui.Messages;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Jpa Support with database plugin
 */
public class JpaSupportWithDatabasePlugin extends AbstractPluginSupport {

  @Override
  public void actionPerformed(AnActionEvent e) {
    super.actionPerformed(e);

    List<DbTable> tables = DatabasePluginUtil.resolveDbTables(e.getData(LangDataKeys.PSI_ELEMENT_ARRAY));
    if (tables.isEmpty()) {
      Messages.showWarningDialog(LocaleContextHolder.format("not_select_valid_tables"), "JpaSupport");
      return;
    }
    resolveDatabaseVendor(tables.get(0).getDataSource());

    Holder.setSelectAllTables(true);

    List<TableSchema> tableSchemas = tables.stream().map(this::toTableSchema).collect(toList());
    AutoGeneratorSettingsDialog.show(tableSchemas, tableSchema -> {
      DbTable dbTable = ((DatabasePluginTableSchema) tableSchema).getDbTable();
      List<ColumnSchema> columnSchemas = new ArrayList<>();
      DasUtil.getColumns(dbTable)
          .consumeEach(dasColumn -> columnSchemas.add(new DatabasePluginColumnSchema(dasColumn)));
      return columnSchemas;
    });
  }

  private TableSchema toTableSchema(DbTable dbTable) {
    TableSchema tableSchema = new DatabasePluginTableSchema(dbTable);
    tableSchema.setTableName(dbTable.getName());
    tableSchema.setTableComment(StringUtils.trimToEmpty(dbTable.getComment()));
    tableSchema.setTableSchema(DasUtil.getSchema(dbTable));
    return tableSchema;
  }

  private void resolveDatabaseVendor(DbDataSource dataSource) {
    String productName = "";
    try {
      Method method = dataSource.getClass().getMethod("getDatabaseProductName");
      method.setAccessible(true);
      productName = (String) method.invoke(dataSource);
    } catch (Exception ignored) {
      try {
        Method method = dataSource.getDelegate().getClass().getMethod("getDatabaseProductName");
        method.setAccessible(true);
        productName = (String) method.invoke(dataSource.getDelegate());
      } catch (Exception ignored2) {
      }
    }
    if (StringUtils.isBlank(productName)) {
      productName = dataSource.getName();
    }
    if (productName.startsWith("PostgreSQL")) {
      Holder.registerDatabaseDrivers(DatabaseDrivers.POSTGRE_SQL);
    } else if (productName.startsWith("MySQL")) {
      Holder.registerDatabaseDrivers(DatabaseDrivers.MYSQL);
    } else {
      Holder.registerDatabaseDrivers(DatabaseDrivers.UNKNOWN);
    }
  }
}
