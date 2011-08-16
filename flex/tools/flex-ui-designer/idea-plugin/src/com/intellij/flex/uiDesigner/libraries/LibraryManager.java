package com.intellij.flex.uiDesigner.libraries;

import com.intellij.ProjectTopics;
import com.intellij.flex.uiDesigner.*;
import com.intellij.flex.uiDesigner.io.InfoList;
import com.intellij.flex.uiDesigner.io.StringRegistry;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Consumer;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("MethodMayBeStatic")
public class LibraryManager extends EntityListManager<VirtualFile, Library> {
  private static final String ABC_FILTER_VERSION = "7";
  private static final String ABC_FILTER_VERSION_VALUE_NAME = "fud_abcFilterVersion";

  private File appDir;

  public static LibraryManager getInstance() {
    return ServiceManager.getService(LibraryManager.class);
  }

  public void setAppDir(@NotNull File appDir) {
    this.appDir = appDir;
  }

  public boolean isRegistered(@NotNull Library library) {
    return list.contains(library);
  }

  public int add(@NotNull Library library) {
    return list.add(library);
  }

  public boolean isSdkRegistered(Sdk sdk, Module module) {
    ProjectInfo info = Client.getInstance().getRegisteredProjects().getNullableInfo(module.getProject());
    return info != null && info.getSdk() == sdk;
  }

  public void garbageCollection() {
    PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
    if (ABC_FILTER_VERSION.equals(propertiesComponent.getValue(ABC_FILTER_VERSION_VALUE_NAME))) {
      return;
    }

    for (String path : appDir.list()) {
      if (path.endsWith(SwcDependenciesSorter.SWF_EXTENSION) && !path.equals(FlexUIDesignerApplicationManager.DESIGNER_SWF)) {
        //noinspection ResultOfMethodCallIgnored
        new File(appDir, path).delete();
      }
    }

    propertiesComponent.setValue(ABC_FILTER_VERSION_VALUE_NAME, ABC_FILTER_VERSION);
  }

  public XmlFile[] initLibrarySets(@NotNull final Module module) throws IOException, InitException {
    final ProblemsHolder problemsHolder = new ProblemsHolder();
    XmlFile[] unregisteredDocumentReferences = initLibrarySets(module, true, problemsHolder, null);
    if (!problemsHolder.isEmpty()) {
      DocumentProblemManager.getInstance().report(module.getProject(), problemsHolder);
    }

    return unregisteredDocumentReferences;
  }

  public XmlFile[] initLibrarySets(@NotNull final Module module, @NotNull ProblemsHolder problemsHolder) throws IOException, InitException {
    return initLibrarySets(module, true, problemsHolder, null);
  }

  // librarySet for test only
  public XmlFile[] initLibrarySets(@NotNull final Module module, boolean collectLocalStyleHolders, ProblemsHolder problemsHolder,
                                   @Nullable LibrarySet librarySet)
      throws InitException, IOException {
    final Project project = module.getProject();
    final LibraryCollector libraryCollector = new LibraryCollector(this);
    final StringRegistry.StringWriter stringWriter = new StringRegistry.StringWriter(16384);
    stringWriter.startChange();

    final Client client;
    try {
      AccessToken token = ReadAction.start();
      try {
        libraryCollector.collect(module, new LibraryStyleInfoCollector(project, module, stringWriter, problemsHolder));
      }
      finally {
        token.finish();
      }

      client = Client.getInstance();
      if (stringWriter.hasChanges()) {
        client.updateStringRegistry(stringWriter);
      }
      else {
        stringWriter.finishChange();
      }
    }
    catch (Throwable e) {
      stringWriter.rollbackChange();
      throw new InitException(e, "error.collect.libraries");
    }

    final InfoList<Project, ProjectInfo> registeredProjects = client.getRegisteredProjects();
    final String projectLocationHash = project.getLocationHash();
    ProjectInfo info = registeredProjects.getNullableInfo(project);
    final boolean isNewProject = info == null;

    RequiredAssetsInfo requiredAssetsInfo = null;

    if (isNewProject) {
      if (librarySet == null) {
        final SwcDependenciesSorter swcDependenciesSorter = new SwcDependenciesSorter(appDir, module);
        librarySet = createLibrarySet(projectLocationHash + "_fdk", null, libraryCollector.sdkLibraries,
                                      libraryCollector.getFlexSdkVersion(), swcDependenciesSorter, true);
        requiredAssetsInfo = swcDependenciesSorter.getRequiredAssetsInfo();
        client.registerLibrarySet(librarySet);
      }

      info = new ProjectInfo(project, librarySet, libraryCollector.getFlexSdk());
      registeredProjects.add(info);
      client.openProject(project);
    }
    else {
      // different flex sdk version for module
      if (libraryCollector.sdkLibraries != null) {
        final SwcDependenciesSorter swcDependenciesSorter = new SwcDependenciesSorter(appDir, module);
        librarySet = createLibrarySet(Integer.toHexString(module.getName().hashCode()) + "_fdk", null, libraryCollector.sdkLibraries,
                                      libraryCollector.getFlexSdkVersion(), swcDependenciesSorter, true);
        requiredAssetsInfo = swcDependenciesSorter.getRequiredAssetsInfo();
        client.registerLibrarySet(librarySet);
      }
    }

    if (libraryCollector.externalLibraries.isEmpty()) {
      if (!isNewProject && librarySet == null) {
        librarySet = info.getLibrarySet();
      }
    }
    else if (isNewProject) {
      librarySet = createLibrarySet(projectLocationHash, librarySet, libraryCollector.externalLibraries,
                                    libraryCollector.getFlexSdkVersion(), new SwcDependenciesSorter(appDir, module), false);
      client.registerLibrarySet(librarySet);
      info.setLibrarySet(librarySet);
    }
    else {
      // todo merge existing libraries and new. create new custom external library set for myModule,
      // if we have different version of the artifact
      throw new UnsupportedOperationException("merge existing libraries and new");
    }

    final ModuleInfo moduleInfo = new ModuleInfo(module);
    final List<XmlFile> unregisteredDocumentReferences = new ArrayList<XmlFile>();
    if (collectLocalStyleHolders) {
      // client.registerModule finalize it
      stringWriter.startChange();
      try {
        ModuleInfoUtil.collectLocalStyleHolders(moduleInfo, libraryCollector.getFlexSdkVersion(), stringWriter, problemsHolder, unregisteredDocumentReferences);
      }
      catch (Throwable e) {
        stringWriter.rollbackChange();
        throw new InitException(e, "error.collect.local.style.holders");
      }
    }

    client.registerModule(project, moduleInfo, new String[]{librarySet.getId()}, stringWriter, requiredAssetsInfo);

    module.getMessageBus().connect(moduleInfo).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void beforeRootsChange(ModuleRootEvent event) {
      }

      @Override
      public void rootsChanged(ModuleRootEvent event) {
        new Notification(FlexUIDesignerBundle.message("plugin.name"), FlexUIDesignerBundle.message("plugin.name"),
            "Listen library changes is not yet supported. Please, reopen project.",
            NotificationType.WARNING).notify(project);
      }
    });

    return unregisteredDocumentReferences.isEmpty() ? null : unregisteredDocumentReferences.toArray(new XmlFile[unregisteredDocumentReferences.size()]);
  }

  @NotNull
  private static LibrarySet createLibrarySet(String id, @Nullable LibrarySet parent, List<Library> libraries, String flexSdkVersion,
      SwcDependenciesSorter swcDependenciesSorter, final boolean isFromSdk)
    throws InitException {
    try {
      swcDependenciesSorter.sort(libraries, id, flexSdkVersion, isFromSdk);
      return new LibrarySet(id, parent, ApplicationDomainCreationPolicy.ONE, swcDependenciesSorter.getItems(),
        swcDependenciesSorter.getResourceBundleOnlyitems(), swcDependenciesSorter.getEmbedItems());
    }
    catch (Throwable e) {
      throw new InitException(e, "error.sort.libraries");
    }
  }

  public Library createOriginalLibrary(@NotNull final VirtualFile virtualFile, @NotNull final VirtualFile jarFile,
                                               @NotNull final Consumer<Library> initializer) {
    if (list.contains(jarFile)) {
      return list.getInfo(jarFile);
    }
    else {
      final String path = virtualFile.getPath();
      Library library =
        new Library(virtualFile.getNameWithoutExtension() + "." + Integer.toHexString(path.hashCode()), jarFile);
      initializer.consume(library);
      return library;
    }
  }

  @SuppressWarnings("MethodMayBeStatic")
  @Nullable
  public PropertiesFile getResourceBundleFile(String locale, String bundleName, ProjectInfo projectInfo) {
    LibrarySet librarySet = projectInfo.getLibrarySet();
    PropertiesFile propertiesFile;
    do {
      for (LibrarySetItem item : librarySet.getItems()) {
        Library library = item.library;
        if (library.hasResourceBundles() && (propertiesFile = getResourceBundleFile(locale, bundleName, library, projectInfo)) != null) {
          return propertiesFile;
        }
      }

      for (LibrarySetItem item : librarySet.getResourceBundleOnlyItems()) {
        if ((propertiesFile = getResourceBundleFile(locale, bundleName, item.library, projectInfo)) != null) {
          return propertiesFile;
        }
      }
    }
    while ((librarySet = librarySet.getParent()) != null);

    return null;
  }

  private static PropertiesFile getResourceBundleFile(String locale, String bundleName, Library library, ProjectInfo projectInfo) {
    final THashSet<String> bundles = library.resourceBundles.get(locale);
    if (!bundles.contains(bundleName)) {
      return null;
    }

    //noinspection ConstantConditions
    VirtualFile virtualFile = library.getFile().findChild("locale").findChild(locale)
        .findChild(bundleName + CatalogXmlBuilder.PROPERTIES_EXTENSION);
    //noinspection ConstantConditions
    return (PropertiesFile)PsiDocumentManager.getInstance(projectInfo.getElement())
        .getPsiFile(FileDocumentManager.getInstance().getDocument(virtualFile));
  }
}