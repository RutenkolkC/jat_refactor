package main;

import java.util.ArrayList;
import java.util.List;

import com.netflix.rewrite.ast.AstTransform;
import com.netflix.rewrite.ast.Tr;
import com.netflix.rewrite.ast.Tr.Block;
import com.netflix.rewrite.ast.Tr.ClassDecl;
import com.netflix.rewrite.ast.Tr.MethodDecl;
import com.netflix.rewrite.refactor.RefactorVisitor;

public class AddMethods extends RefactorVisitor<Tr.ClassDecl>{

	List<MethodDecl> methods;
	public AddMethods(List<MethodDecl> methods) {
		this.methods=methods;
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
			help.addAll(methods);
			Block<?> new_block_body = block_body.copy(block_body.getStatic(), help, block_body.getFormatting(), block_body.getEndOfBlockSuffix(), block_body.getId());

			return body.copy(body.getAnnotations(), body.getModifiers(), body.getKind(), body.getName(), body.getTypeParams(), body.getExtends(), body.getImplements(), new_block_body, body.getType(), body.getFormatting(), body.getId());
		}
				);
	}
}