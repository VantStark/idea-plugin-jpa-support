package com.ifengxue.plugin.gui;

import com.ifengxue.plugin.Constants;
import com.ifengxue.plugin.Holder;
import com.ifengxue.plugin.component.AutoGeneratorSettings;
import com.ifengxue.plugin.entity.ColumnSchema;
import com.ifengxue.plugin.entity.Table;
import com.ifengxue.plugin.entity.TableSchema;
import com.ifengxue.plugin.i18n.LocaleContextHolder;
import com.ifengxue.plugin.state.AutoGeneratorSettingsState;
import com.ifengxue.plugin.state.ModuleSettings;
import com.ifengxue.plugin.util.BusUtil;
import com.ifengxue.plugin.util.StringHelper;
import com.intellij.ide.util.TreeJavaClassChooserDialog;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import java.awt.event.ItemEvent;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.swing.Action;
import javax.swing.JComponent;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

public class AutoGeneratorSettingsDialog extends DialogWrapper {

  private final AutoGeneratorSettings generatorSettings;
  private final List<TableSchema> tableSchemaList;
  private final AutoGeneratorSettingsState autoGeneratorSettingsState;
  private final Function<TableSchema, List<ColumnSchema>> mapping;

  protected AutoGeneratorSettingsDialog(Project project, List<TableSchema> tableSchemaList,
                                        Function<TableSchema, List<ColumnSchema>> mapping) {
    super(project, true);
    generatorSettings = new AutoGeneratorSettings();

    this.tableSchemaList = tableSchemaList;
    this.mapping = mapping;
    this.autoGeneratorSettingsState = ServiceManager.getService(project, AutoGeneratorSettingsState.class);
    init();
    setTitle(LocaleContextHolder.format("auto_generation_settings"));

    // select module
    Module[] modules = ModuleManager.getInstance(project).getModules();
    Module selectedModule = modules[0];
    for (Module module : modules) {
      generatorSettings.getCbxModule().addItem(module.getName());
      if (module.getName().equals(autoGeneratorSettingsState.getModuleName())) {
        generatorSettings.getCbxModule().setSelectedItem(module.getName());
        selectedModule = module;
      }
    }
    if (generatorSettings.getCbxModule().getSelectedIndex() == -1) {
      generatorSettings.getCbxModule().setSelectedIndex(0);
    }

    initTextField();
    moduleChange(selectedModule);
    setPackagePath(selectedModule, true);

    generatorSettings.getCbxModule().addItemListener(itemEvent -> {
      if (itemEvent.getStateChange() != ItemEvent.SELECTED) {
        return;
      }
      String moduleName = (String) itemEvent.getItem();
      findModule(moduleName)
          .ifPresent(module -> {
            moduleChange(module);
            setPackagePath(module, false);
          });
    });
    // 选择父类
    generatorSettings.getBtnChooseSuperClass().addActionListener(event -> {
      TreeJavaClassChooserDialog classChooser = new TreeJavaClassChooserDialog(
          LocaleContextHolder.format("select_parent_class"), project);
      classChooser.show();
      PsiClass selectedClass = classChooser.getSelected();
      if (selectedClass != null) {
        String qualifiedName = selectedClass.getQualifiedName();
        generatorSettings.getTextExtendBaseClass().setText(qualifiedName);
        Set<String> excludeFieldSet = new LinkedHashSet<>();
        for (String excludeField : generatorSettings.getTextExcludeFields().getText().trim().split(",")) {
          if (StringUtils.isNotBlank(excludeField)) {
            excludeFieldSet.add(excludeField.trim());
          }
        }
        Arrays.stream(selectedClass.getAllFields())
            .filter(psiField -> psiField.getModifierList() != null)
            .filter(psiField -> !psiField.getModifierList().hasModifierProperty(PsiModifier.STATIC))
            .filter(psiField -> !psiField.getModifierList().hasModifierProperty(PsiModifier.FINAL))
            .map(PsiField::getName)
            .forEach(excludeFieldSet::add);
        generatorSettings.getTextExcludeFields().setText(String.join(",", excludeFieldSet));
      }
    });
  }

  private void setPackagePath(Module module, boolean checkEmpty) {
    String moduleName = module.getName();
    autoGeneratorSettingsState.setModuleName(moduleName);
    ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
    List<VirtualFile> sourceRoots = moduleRootManager.getSourceRoots(JavaSourceRootType.SOURCE);
    String sourceRoot;
    if (sourceRoots.isEmpty()) {
      VirtualFile[] contentRoots = moduleRootManager.getContentRoots();
      if (contentRoots.length == 0) {
        BusUtil.notify(Holder.getProject(), "Module " + moduleName + " does not contain Source Root.",
            NotificationType.WARNING);
        return;
      }
      sourceRoot = contentRoots[0].getCanonicalPath() + "/src/main/java";
    } else {
      sourceRoot = sourceRoots.get(0).getCanonicalPath();
    }

    if (!checkEmpty || generatorSettings.getTextEntityPackageParentPath().getText().isEmpty()) {
      generatorSettings.getTextEntityPackageParentPath().setText(sourceRoot);
    }
    if (!checkEmpty || generatorSettings.getTextRepositoryPackageParentPath().getText().isEmpty()) {
      generatorSettings.getTextRepositoryPackageParentPath().setText(sourceRoot);
    }
  }

  @Nullable
  @Override
  protected JComponent createCenterPanel() {
    return generatorSettings.getRootComponent();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{getOKAction(), getCancelAction()};
  }

  @Nullable
  @Override
  protected ValidationInfo doValidate() {
    String entityPackage = generatorSettings.getEntityPackageReferenceEditorCombo().getText().trim();
    if (entityPackage.isEmpty()) {
      generatorSettings.getEntityPackageReferenceEditorCombo().requestFocus();
      return new ValidationInfo("Must set entity package",
          generatorSettings.getEntityPackageReferenceEditorCombo());
    }
    if (generatorSettings.getChkBoxGenerateRepository().isSelected()) {
      if (generatorSettings.getChkBoxSerializable().isSelected() && generatorSettings
          .getRepositoryPackageReferenceEditorCombo().getText().trim().isEmpty()) {
        return new ValidationInfo("Must set repository package",
            generatorSettings.getRepositoryPackageReferenceEditorCombo());
      }
    }
    Module module = ModuleManager.getInstance(Holder.getProject())
        .findModuleByName((String) Objects.requireNonNull(generatorSettings.getCbxModule().getSelectedItem()));
    if (module == null) {
      return new ValidationInfo("Must select valid module", generatorSettings.getCbxModule());
    }
    return null;
  }

  @Override
  protected void doOKAction() {
    ModuleSettings moduleSettings = autoGeneratorSettingsState.getModuleSettings(
        (String) generatorSettings.getCbxModule().getSelectedItem());
    // read attributes
    generatorSettings.getData(autoGeneratorSettingsState, moduleSettings);
    List<Table> tableList = new ArrayList<>(tableSchemaList.size());
    String entityDirectory = Paths.get(moduleSettings.getEntityParentDirectory(),
        StringHelper.packageNameToFolder(moduleSettings.getEntityPackageName()))
        .toAbsolutePath().toString();
    VirtualFile entityDirectoryVF = LocalFileSystem.getInstance().findFileByPath(entityDirectory);
    for (TableSchema tableSchema : tableSchemaList) {
      String tableName = autoGeneratorSettingsState.removeTablePrefix(tableSchema.getTableName());
      String entityName = StringHelper.parseEntityName(tableName);
      entityName = autoGeneratorSettingsState.concatPrefixAndSuffix(entityName);
      // If the path contains a file with the same name, it is not selected by default
      boolean selected = entityDirectoryVF == null || entityDirectoryVF.findChild(entityName + ".java") == null;
      if (selected) {
        // support flyway
        if (tableName.equals("flyway_schema_history")) {
          selected = false;
        }
      }
      // Database Plugin, selected by default
      if (!selected) {
        selected = Holder.isSelectAllTables();
      }
      String repositoryName = entityName + autoGeneratorSettingsState.getRepositorySuffix();
      tableList.add(Table.from(tableSchema, entityName, repositoryName, selected));
    }
    ApplicationManager.getApplication().invokeLater(() -> {
      dispose();
      SelectTablesDialog.show(tableList, mapping);
    });
  }

  @Override
  public void doCancelAction() {
    super.doCancelAction();
  }

  @Nullable
  @Override
  protected String getDimensionServiceKey() {
    return Constants.NAME + ":" + getClass().getName();
  }

  private void initTextField() {
    // init button title
    setOKButtonText(LocaleContextHolder.format("button_next_step"));
    setCancelButtonText(LocaleContextHolder.format("button_cancel"));
  }

  private void moduleChange(Module newModule) {
    ModuleSettings moduleSettings = autoGeneratorSettingsState.getModuleSettings(newModule.getName());
    generatorSettings.setData(autoGeneratorSettingsState, moduleSettings);

    if (moduleSettings != null) {
      generatorSettings.getEntityPackageReferenceEditorCombo().setText(moduleSettings.getEntityPackageName());
      if (StringUtils.isNotBlank(moduleSettings.getEntityPackageName())) {
        generatorSettings.getEntityPackageReferenceEditorCombo().prependItem(moduleSettings.getEntityPackageName());
      }
      generatorSettings.getRepositoryPackageReferenceEditorCombo().setText(moduleSettings.getRepositoryPackageName());
      if (StringUtils.isNotBlank(moduleSettings.getRepositoryPackageName())) {
        generatorSettings.getRepositoryPackageReferenceEditorCombo().prependItem(moduleSettings.getRepositoryPackageName());
      }
      generatorSettings.getTextEntityPackageParentPath().setText(moduleSettings.getEntityParentDirectory());
      generatorSettings.getTextRepositoryPackageParentPath().setText(moduleSettings.getRepositoryParentDirectory());
    }
  }

  private Optional<Module> findModule(String moduleName) {
    return Optional.ofNullable(ModuleManager.getInstance(Holder.getProject())
        .findModuleByName(moduleName));
  }

  public static void show(List<TableSchema> tableSchemaList, Function<TableSchema, List<ColumnSchema>> mapping) {
    new AutoGeneratorSettingsDialog(Holder.getProject(), tableSchemaList, mapping).show();
  }
}
