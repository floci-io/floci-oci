package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredPar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Pre-authenticated request data path: {@code /p/{token}/n/{ns}/b/{bucket}/o/{object}}.
 * Bypasses auth entirely — the token is the credential.
 */
@Path("/p/{parToken}/n/{namespaceName}/b/{bucketName}")
@ApplicationScoped
public class ParAccessController {

    private static final Set<String> READ_TYPES =
            Set.of("ObjectRead", "ObjectReadWrite", "AnyObjectRead", "AnyObjectReadWrite");
    private static final Set<String> WRITE_TYPES =
            Set.of("ObjectWrite", "ObjectReadWrite", "AnyObjectWrite", "AnyObjectReadWrite");

    private final ObjectStorageService service;

    @Inject
    public ParAccessController(ObjectStorageService service) {
        this.service = service;
    }

    @GET
    @Path("/o/{objectName:.+}")
    public Response getObject(@PathParam("parToken") String parToken,
                              @PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @PathParam("objectName") String objectName,
                              @HeaderParam("Range") String range) {
        StoredPar par = authorize(parToken, bucketName, objectName, READ_TYPES);
        StoredOsObject o = service.getObject(namespaceName, par.getBucket(), objectName);
        return ObjectStorageController.objectResponse(o, range, true);
    }

    @PUT
    @Path("/o/{objectName:.+}")
    public Response putObject(@PathParam("parToken") String parToken,
                              @PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @PathParam("objectName") String objectName,
                              @HeaderParam("Content-Type") String contentType,
                              @HeaderParam("Content-MD5") String contentMd5,
                              @Context HttpHeaders headers,
                              InputStream body) throws IOException {
        StoredPar par = authorize(parToken, bucketName, objectName, WRITE_TYPES);
        StoredOsObject o = service.putObject(namespaceName, par.getBucket(), objectName,
                body.readAllBytes(), contentType, contentMd5, null, null, null);
        return Response.ok().header("etag", o.getEtag()).header("opc-content-md5", o.getMd5()).build();
    }

    private StoredPar authorize(String parToken, String bucketName, String objectName,
                                Set<String> allowedTypes) {
        StoredPar par = service.resolveParToken(parToken);
        if (!par.getBucket().equals(bucketName)) {
            throw OciException.notAuthorizedOrNotFound(
                    "The pre-authenticated request does not grant access to this bucket.");
        }
        if (par.getObjectName() != null && !par.getObjectName().equals(objectName)) {
            throw OciException.notAuthorizedOrNotFound(
                    "The pre-authenticated request does not grant access to this object.");
        }
        if (!allowedTypes.contains(par.getAccessType())) {
            throw OciException.notAuthorizedOrNotFound(
                    "The pre-authenticated request does not permit this operation.");
        }
        return par;
    }
}
