package com.suntao.handler;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

public class RenameHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        InputDialog inputDialog = new InputDialog(shell, "请输入项目标识", "项目标识", null, null);
        inputDialog.create();
        if (inputDialog.open() == Window.OK) {
            String suffix = inputDialog.getValue();
            if (suffix.length() > 0) {
                IStructuredSelection structured = (IStructuredSelection) PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow().getSelectionService()
                        .getSelection("org.eclipse.jdt.ui.PackageExplorer");
                Object selected = structured.getFirstElement();
                if (selected instanceof IPackageFragment) {
                    try {
                        IPackageFragment pack = (IPackageFragment) selected;
                        IRunnableWithProgress op = new RenameRunnableWithProgress(pack, suffix);
                        ProgressMonitorDialog progressDialog = new ProgressMonitorDialog(
                                HandlerUtil.getActiveShell(event));
                        progressDialog.run(true, true, op);
                    } catch (InvocationTargetException e) {
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        return null;
    }
}
