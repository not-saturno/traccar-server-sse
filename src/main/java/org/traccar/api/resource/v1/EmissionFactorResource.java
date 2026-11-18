package org.traccar.api.resource.v1;

import org.traccar.api.BaseResource;
import org.traccar.emission.EmissionFactor;
import org.traccar.emission.EmissionFactorResolver;
import org.traccar.emission.EmissionFactorService;
import org.traccar.emission.EmissionParameter;
import org.traccar.helper.LogAction;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Fork: history of the emission and cost parameters used by {@code /api/v1/reports/emissions}. Any logged user can
 * read it; only administrators change it.
 */
@Path("v1/emission-factors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmissionFactorResource extends BaseResource {

    @Inject
    private EmissionFactorService factorService;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public record ParameterInfo(String key, String unit, double defaultValue, String description) {
    }

    public record EffectiveValue(
            String parameter, String unit, double value, Long factorId, String period, String category,
            String source) {
    }

    /** Factors ordered by parameter and period, optionally filtered. */
    @GET
    public List<EmissionFactor> get(
            @QueryParam("parameter") String parameter, @QueryParam("category") String category)
            throws StorageException {
        return factorService.getFactors().stream()
                .filter(factor -> parameter == null || parameter.equals(factor.getParameter()))
                .filter(factor -> category == null || category.equals(factor.getCategory()))
                .sorted(Comparator.comparing(EmissionFactor::getParameter).thenComparing(EmissionFactor::getPeriod))
                .toList();
    }

    @GET
    @Path("parameters")
    public List<ParameterInfo> getParameters() {
        return Arrays.stream(EmissionParameter.values())
                .map(p -> new ParameterInfo(p.getKey(), p.getUnit(), p.getDefaultValue(), p.getDescription()))
                .toList();
    }

    /** Values that apply on {@code date} (YYYY-MM-DD, default today) for a device category. */
    @GET
    @Path("effective")
    public List<EffectiveValue> getEffective(
            @QueryParam("date") String date, @QueryParam("category") String category) throws StorageException {
        LocalDate day;
        try {
            day = date != null && !date.isEmpty() ? LocalDate.parse(date) : LocalDate.now();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must be YYYY-MM-DD");
        }
        EmissionFactorResolver resolver = factorService.getResolver();
        return resolver.resolveAll(category, day).values().stream().map(resolved -> {
            EmissionFactor factor = resolved.factor();
            return new EffectiveValue(
                    resolved.parameter().getKey(), resolved.parameter().getUnit(), resolved.value(),
                    factor != null ? factor.getId() : null, factor != null ? factor.getPeriod() : null,
                    factor != null ? factor.getCategory() : null, factor != null ? factor.getSource() : null);
        }).toList();
    }

    @POST
    public Response add(EmissionFactor entity) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        EmissionFactor factor = factorService.add(entity);
        actionLogger.create(request, getUserId(), factor);
        return Response.ok(factor).build();
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") long id, EmissionFactor entity) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        if (factorService.getFactor(id) == null) {
            throw new NotFoundException();
        }
        entity.setId(id);
        EmissionFactor factor = factorService.update(entity);
        actionLogger.edit(request, getUserId(), factor);
        return Response.ok(factor).build();
    }

    @DELETE
    @Path("{id}")
    public Response remove(@PathParam("id") long id) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        if (factorService.getFactor(id) == null) {
            throw new NotFoundException();
        }
        factorService.remove(id);
        actionLogger.remove(request, getUserId(), EmissionFactor.class, id);
        return Response.noContent().build();
    }

}
