package com.suntao.handler;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameResourceDescriptor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@SuppressWarnings("deprecation")
public class RenameRunnableWithProgress implements IRunnableWithProgress {
    private final static DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    private IPackageFragment pack;
    private String suffix;
    
    static {
        factory.setExpandEntityReferences(false);
        factory.setIgnoringComments(false);
        factory.setValidating(false);
    }
    
    public RenameRunnableWithProgress(IPackageFragment pack, String suffix) {
        this.pack = pack;
        this.suffix = suffix;
    }

    @Override
    public void run(IProgressMonitor pm) throws InvocationTargetException, InterruptedException {
        pm.beginTask("处理中", 100);
        // 添加项目标识, 先给类文件, 再给mapper文件, 最后给包添加
        try {
            String prefix = pack.getElementName(); // 当前选中包的完全限定名，如com.suntao.handler
            IPackageFragmentRoot root = (IPackageFragmentRoot) pack.getParent();
            pm.worked(5);
            String[] strs = new String[]{".controller", ".dao", ".dto", ".service", ".rest"};
            for (String str : strs) {
                IPackageFragment fragment = root.getPackageFragment(prefix + str);
                if (fragment.isOpen()) {
                    renameClassFile(fragment, suffix);
                    if (str.equals(".dao")) {
                        renameXMLFile(fragment, suffix);
                    }
                }
                pm.worked(15);
            }
            renamePackage(pack, suffix);
        } catch (JavaModelException e) {
            e.printStackTrace();
        } catch (CoreException e) {
            e.printStackTrace();
        }
        pm.done();
    }

    /**
     * 修改<code>pack</code>表示的包下的源文件名
     * 
     * @param pack 包
     * @param suffix 项目标识
     * @throws JavaModelException
     * @author sunt
     * @since 2017年1月17日
     */
    private void renameClassFile(IPackageFragment pack, String suffix)
            throws JavaModelException {
        for (ICompilationUnit cu : pack.getCompilationUnits()) {
            String fileName = cu.getElementName();
            RefactoringContribution contribution = RefactoringCore
                    .getRefactoringContribution(IJavaRefactorings.RENAME_COMPILATION_UNIT);
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution
                    .createDescriptor();
            descriptor.setProject(cu.getResource().getProject().getName());
            int pos = getPositionInFileName(fileName);
            if (pos == -1) {
                continue;
            }
            fileName = fileName.substring(0, pos) + suffix + fileName.substring(pos);
            descriptor.setNewName(fileName);
            descriptor.setJavaElement(cu);
            descriptor.setUpdateReferences(true);
            exectueRefactoring(descriptor);
        }
    }
    
    private int getPositionInFileName(String fileName) {
        int pos = -1;
        if ((pos = fileName.indexOf("Controller")) != -1) {
            return pos;
        } else if ((pos = fileName.indexOf("Dao")) != -1) {
            return pos;
        } else if ((pos = fileName.indexOf("DTO")) != -1) {
            return pos;
        } else if ((pos = fileName.indexOf("Service")) != -1) {
            return pos;
        } else if ((pos = fileName.indexOf("Rest")) != -1) {
            return pos;
        }
        return -1;
    }
    
    // 修改mapper文件中对DAO文件和DTO的引用, 字符串替换一下就行
    private void renameXMLFile(IPackageFragment pack, String suffix) throws JavaModelException,
            CoreException {
        String packageName = pack.getElementName();// 这时的包名还没加项目标识, 如com.suntao.mdsitem.dao
        for (Object element : pack.getNonJavaResources()) {
            if (!(element instanceof IFile)) {
                continue;
            }
            IFile file = (IFile) element;
            String fileName = file.getName();
            int pos = fileName.indexOf("Mapper");
            if (pos == -1) {
                continue;
            }
            // 修改文件内容
            modifyXMLContent(file, packageName);
            // 修改文件名
            String newFileName = fileName.substring(0, pos) + suffix + fileName.substring(pos);
            RefactoringContribution contribution = RefactoringCore
                    .getRefactoringContribution(IJavaRefactorings.RENAME_RESOURCE);
            RenameResourceDescriptor descriptor = (RenameResourceDescriptor) contribution
                    .createDescriptor();
            descriptor.setProject(file.getProject().getName());
            descriptor.setNewName(newFileName);
            descriptor.setResource(file);
            exectueRefactoring(descriptor);
        }
    }

    /**
     * 将Mapper文件中的旧Dao名替换为新Dao名，将旧DTO名替换为新DTO名
     * 
     * @param file Mapper文件
     * @param packageName Mapper文件所在的包名，如com.suntao.mdsitem.dao
     * @throws CoreException
     * @author sunt
     * @since 2017年1月17日
     */
    private void modifyXMLContent(IFile file, String packageName) throws CoreException {
        String fileName = file.getName();
        int pos = fileName.indexOf("Mapper");
        InputStream is = file.getContents();
        String oldDaoName = getOldDaoName(is);
        String tableName = fileName.substring(0, pos);
        String newDaoName = packageName.substring(0, packageName.length() - 4)
                + suffix.toLowerCase() + ".dao." + tableName + suffix + "Dao";
        String oldDTOName = oldDaoName.substring(0, oldDaoName.indexOf(".dao")) + ".dto."
                + tableName + "DTO";
        String newDTOName = packageName.substring(0, packageName.length() - 4)
                + suffix.toLowerCase() + ".dto." + tableName + suffix + "DTO";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is,
                    StandardCharsets.UTF_8));
            String line; // 依次循环，至到读的值为空
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            String str = sb.toString();
            str = str.replaceAll(oldDaoName, newDaoName);
            str = str.replaceAll(oldDTOName, newDTOName);
            file.setContents(new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8)),
                    true, false, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 获取mapper文件中DAO的完全限定名
     * 
     * @param is mapper文件输入流
     * @return mapper文件中DAO的完全限定名
     * @author sunt
     * @since 2017年1月17日
     */
    private String getOldDaoName(InputStream is) {
        String oldDaoName = "";
        // 利用DOM得到mapper元素的namespace属性
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId)
                        throws SAXException, IOException {
                    if (systemId.contains("mybatis-3-mapper.dtd")) {
                        // 不让联网下载dtd文件去验证xml, 否则离线的情况下不能使用
                        return new InputSource(new StringReader(""));
                    }
                    return null;
                }
            });
            Document document = builder.parse(is);
            Element mapperElement = document.getDocumentElement();
            oldDaoName = mapperElement.getAttribute("namespace");
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
        return oldDaoName;
    }
    
    /**
     * 修改包名
     * 
     * @param pack 需要修改的包
     * @param suffix 项目标识
     * @author sunt
     * @since 2017年1月17日
     */
    private void renamePackage(IPackageFragment pack, String suffix) {
        RefactoringContribution contribution = RefactoringCore
                .getRefactoringContribution(IJavaRefactorings.RENAME_PACKAGE);
        RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution
                .createDescriptor();
        descriptor.setProject(pack.getResource().getProject().getName());
        descriptor.setNewName(pack.getElementName() + suffix.toLowerCase());
        descriptor.setJavaElement(pack);
        descriptor.setUpdateReferences(true);
        descriptor.setUpdateHierarchy(true);
        exectueRefactoring(descriptor);
    }
    
    /**
     * 执行重构
     * 
     * @param descriptor 重构描述符
     * @author sunt
     * @since 2017年1月17日
     */
    private void exectueRefactoring(RefactoringDescriptor descriptor) {
        try {
            RefactoringStatus status = new RefactoringStatus();
            RenameRefactoring refactoring = (RenameRefactoring) descriptor
                    .createRefactoring(status);
            IProgressMonitor monitor = new NullProgressMonitor();
            status = refactoring.checkInitialConditions(monitor);
            if (!status.hasFatalError()) {
                status = refactoring.checkFinalConditions(monitor);
                if (!status.hasFatalError()) {
                    Change change = refactoring.createChange(monitor);
                    change.perform(monitor);
                }
            }
        } catch (CoreException e) {
            e.printStackTrace();
        }
    }
}
