package main;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.netflix.rewrite.ast.AstTransform;
import com.netflix.rewrite.ast.Tr;
import com.netflix.rewrite.ast.Tr.Block;
import com.netflix.rewrite.ast.Tr.ClassDecl;
import com.netflix.rewrite.ast.Tr.CompilationUnit;
import com.netflix.rewrite.ast.Tr.MethodDecl;
import com.netflix.rewrite.refactor.RefactorVisitor;

public class RemoveMethods extends RefactorVisitor<Tr.ClassDecl>{

	List<MethodDecl> methods;
	public RemoveMethods(ClassDecl classDecl,String methodName) {
		methods = classDecl.methods().stream().filter(m->m.getName().print().trim().equals(methodName)).collect(Collectors.toList());
	}	
	public RemoveMethods(CompilationUnit cu,String className,String methodName) {
		methods = cu.getClasses().stream().filter(clazz->clazz.getName().print().trim().equals(className)).findFirst().get().
				methods().stream().filter(m->m.getName().print().trim().equals(methodName)).collect(Collectors.toList());
	}
	@Override
	public String getRuleName() {
		return "add-method";
	}
	@SuppressWarnings({ "unchecked", "rawtypes" })
@Override
	public List<? extends AstTransform<? extends ClassDecl>> visitClassDecl(ClassDecl classDecl) {
		return transform((body)-> 
		{
			Block<?> block_body = body.getBody();
			List help = new ArrayList(block_body.getStatements());
			help.removeAll(methods);
			Block<?> new_block_body = block_body.copy(block_body.getStatic(), help, block_body.getFormatting(), block_body.getEndOfBlockSuffix(), block_body.getId());

			return body.copy(body.getAnnotations(), body.getModifiers(), body.getKind(), body.getName(), body.getTypeParams(), body.getExtends(), body.getImplements(), new_block_body, body.getType(), body.getFormatting(), body.getId());
		}
				);
	}
}