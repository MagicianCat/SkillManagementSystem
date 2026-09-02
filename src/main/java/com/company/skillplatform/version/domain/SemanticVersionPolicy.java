package com.company.skillplatform.version.domain;

import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SemanticVersionPolicy {
    public String candidate(ChangeType type, String latest) {
        if (latest == null) return "1.0.0";
        String[] parts = latest.split("\\.");
        if (parts.length != 3) throw invalid();
        try {
            int major=Integer.parseInt(parts[0]);int minor=Integer.parseInt(parts[1]);
            return type==ChangeType.ZIP_REUPLOAD?(major+1)+".0.0":major+"."+(minor+1)+".0";
        } catch(NumberFormatException ex){throw invalid();}
    }
    public int compare(String left,String right){String[] a=left.split("\\."),b=right.split("\\.");for(int i=0;i<3;i++){int c=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(c!=0)return c;}return 0;}
    private BusinessException invalid(){return new BusinessException("INVALID_SEMVER","Published version is not valid SemVer",HttpStatus.UNPROCESSABLE_ENTITY);}
}
