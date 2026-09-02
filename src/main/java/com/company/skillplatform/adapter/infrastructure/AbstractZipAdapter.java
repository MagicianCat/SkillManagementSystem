package com.company.skillplatform.adapter.infrastructure;

import com.company.skillplatform.adapter.domain.SkillAdapter;
import java.io.*;import java.security.MessageDigest;import java.util.*;import java.util.zip.*;

abstract class AbstractZipAdapter implements SkillAdapter {
    @Override public AdapterResult build(AdapterRequest request){try{ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){request.sourceFiles().entrySet().stream().filter(e->!e.getKey().startsWith("overlays/")).sorted(Map.Entry.comparingByKey()).forEach(e->{try{zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}catch(IOException ex){throw new UncheckedIOException(ex);}});byte[] overlay=request.sourceFiles().get(request.overlayPath());if(overlay==null)throw new IllegalArgumentException("Overlay not found: "+request.overlayPath());zip.putNextEntry(new ZipEntry("overlay.yaml"));zip.write(overlay);zip.closeEntry();}byte[] artifact=output.toByteArray();return new AdapterResult(artifact,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)),"application/zip");}catch(UncheckedIOException ex){throw new IllegalStateException("Adapter build failed",ex.getCause());}catch(Exception ex){throw new IllegalStateException("Adapter build failed",ex);}}
}
