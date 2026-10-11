package com.defense.logistics.frontend.controller;
import com.defense.logistics.frontend.service.BackendClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@SessionAttributes({"username", "role", "accessToken", "fullName", "unitId"})
public class WebController {
    private final BackendClientService backendService;

    private Long getCurrentUserUnitId(Model model) {
        Object role = model.getAttribute("role");
        if ("ADMIN".equals(role)) return null;
        Object cachedUnitId = model.getAttribute("unitId");
        if (cachedUnitId != null) {
            try { return Long.valueOf(String.valueOf(cachedUnitId)); } catch (Exception ignored) {}
        }
        try {
            Map<String, Object> profile = backendService.getMyAccessProfile();
            Object unitId = profile.get("unitId");
            if (unitId != null) {
                Long id = Long.valueOf(String.valueOf(unitId));
                model.addAttribute("unitId", id);
                return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Long getCurrentUserUnitIdSafe() {
        try {
            Map<String, Object> profile = backendService.getMyAccessProfile();
            Object unitId = profile.get("unitId");
            return unitId != null ? Long.valueOf(String.valueOf(unitId)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @ModelAttribute("availableUnits")
    public List<Map<String, Object>> getAvailableUnits(@SessionAttribute(value = "role", required = false) String role,
                                                       @SessionAttribute(value = "unitId", required = false) Long unitId) {
        try {
            List<Map<String, Object>> all = backendService.getUnits();
            List<Map<String, Object>> active = all.stream()
                    .filter(u -> "ACTIVE".equalsIgnoreCase(String.valueOf(u.get("status"))))
                    .toList();
            List<Map<String, Object>> base = active.isEmpty() ? all.stream().filter(u -> !"INACTIVE".equalsIgnoreCase(String.valueOf(u.get("status")))).toList() : active;
            if ("EMPLOYEE".equals(role)) {
                Long empUnitId = unitId != null ? unitId : getCurrentUserUnitIdSafe();
                if (empUnitId != null) {
                    return base.stream().filter(u -> empUnitId.toString().equals(String.valueOf(u.get("id")))).toList();
                }
            }
            return base;
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- GET ROUTES ---
    @GetMapping("/") public String dashboard(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        Long unitId = getCurrentUserUnitId(model);
        List<Map<String, Object>> allUnits = backendService.getUnits();
        List<Map<String, Object>> displayUnits;
        Map<String, Object> summary;

        if (unitId != null) {
            displayUnits = allUnits.stream()
                    .filter(u -> unitId.toString().equals(String.valueOf(u.get("id"))))
                    .toList();
            List<Map<String, Object>> unitPersonnel = backendService.getPersonnel().stream()
                    .filter(p -> unitId.toString().equals(String.valueOf(p.get("unitId"))))
                    .toList();
            long pendingUnitRequests = backendService.getRequests().stream()
                    .filter(r -> "CREATED".equals(r.get("status")) && unitId.toString().equals(String.valueOf(r.get("requestingUnitId"))))
                    .count();
            long unitVehicles = backendService.getVehicles().stream()
                    .filter(v -> unitId.toString().equals(String.valueOf(v.get("unitId"))))
                    .count();
            summary = Map.of(
                    "totalUnits", displayUnits.size(),
                    "totalPersonnel", unitPersonnel.size(),
                    "pendingRequests", (int) pendingUnitRequests,
                    "totalVehicles", (int) unitVehicles
            );
        } else {
            displayUnits = allUnits;
            summary = backendService.getDashboardSummary();
        }

        model.addAttribute("summary", summary); 
        model.addAttribute("units", displayUnits); 
        return "dashboard"; 
    }
    @GetMapping("/personnel") public String personnel(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        Long unitId = getCurrentUserUnitId(model);
        List<Map<String, Object>> personnelList = backendService.getPersonnel();
        if (unitId != null) {
            personnelList = personnelList.stream()
                    .filter(p -> unitId.toString().equals(String.valueOf(p.get("unitId"))))
                    .toList();
        }
        model.addAttribute("personnelList", personnelList); 
        return "personnel"; 
    }
    @GetMapping("/personnel/{id}") public String personnelProfile(@PathVariable Long id, Model model) {
        if (!model.containsAttribute("role") || !"ADMIN".equals(model.getAttribute("role"))) return "redirect:/login";
        Map<String, Object> person = backendService.getPersonnel(id);
        if (person.isEmpty()) return "redirect:/personnel";
        model.addAttribute("person", person);
        model.addAttribute("unit", backendService.getUnit(Long.valueOf(String.valueOf(person.get("unitId")))));
        model.addAttribute("assignedAssets", matchingPersonnel(backendService.getAssets(), "assignedPersonnelId", id));
        model.addAttribute("assignedVehicles", matchingPersonnel(backendService.getVehicles(), "driverPersonnelId", id));
        return "personnel-profile";
    }
    @GetMapping("/assets") public String assets(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        Long unitId = getCurrentUserUnitId(model);
        List<Map<String, Object>> assetList = backendService.getAssets();
        if (unitId != null) {
            assetList = assetList.stream()
                    .filter(a -> unitId.toString().equals(String.valueOf(a.get("unitId"))))
                    .toList();
        }
        model.addAttribute("assetList", assetList); 
        return "assets"; 
    }
    @GetMapping("/fleet") public String fleet(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        Long unitId = getCurrentUserUnitId(model);
        List<Map<String, Object>> allPersonnel = backendService.getPersonnel();
        Map<String, Map<String, Object>> personnelById = allPersonnel.stream()
                .filter(p -> p.get("id") != null)
                .collect(Collectors.toMap(p -> String.valueOf(p.get("id")), p -> p, (a, b) -> a));

        List<Map<String, Object>> vehicleList = backendService.getVehicles().stream()
                .filter(v -> unitId == null || unitId.toString().equals(String.valueOf(v.get("unitId"))))
                .map(v -> {
                    Map<String, Object> copy = new HashMap<>(v);
                    Object driverId = copy.get("driverPersonnelId");
                    if (driverId != null && personnelById.containsKey(String.valueOf(driverId))) {
                        Map<String, Object> driver = personnelById.get(String.valueOf(driverId));
                        copy.put("driverName", driver.get("fullName"));
                        copy.put("driverServiceNumber", driver.get("serviceNumber"));
                        copy.put("driverDisplay", driver.get("fullName") + " (" + driver.get("serviceNumber") + ")");
                    } else if (copy.get("driverName") != null && !String.valueOf(copy.get("driverName")).isBlank()) {
                        String sn = copy.get("driverServiceNumber") != null ? " (" + copy.get("driverServiceNumber") + ")" : "";
                        copy.put("driverDisplay", copy.get("driverName") + sn);
                    } else {
                        copy.put("driverDisplay", "Unassigned");
                    }
                    return copy;
                })
                .toList();

        model.addAttribute("vehicleList", vehicleList); 
        model.addAttribute("personnelList", allPersonnel);
        return "fleet"; 
    }
    @GetMapping("/inventory") public String inventory(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        model.addAttribute("supplyList", backendService.getSupplies()); model.addAttribute("stockList", backendService.getStock()); return "inventory"; 
    }
    @GetMapping("/requests") public String requests(@RequestParam(required = false) String query,
                                                     @RequestParam(required = false) String status,
                                                     @SessionAttribute(value = "role", required = false) String role,
                                                     @SessionAttribute(value = "username", required = false) String username,
                                                     Model model) {
        if (role == null || username == null) return "redirect:/login";
        Long unitId = getCurrentUserUnitId(model);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = isRequestStatus(status) ? status : "";
        List<Map<String, Object>> requests = backendService.getRequests().stream()
                .filter(request -> "ADMIN".equals(role) 
                        || (unitId != null && unitId.toString().equals(String.valueOf(request.get("requestingUnitId"))))
                        || username.equalsIgnoreCase(String.valueOf(request.get("requestedBy"))))
                .filter(request -> normalizedStatus.isEmpty() || normalizedStatus.equals(String.valueOf(request.get("status"))))
                .filter(request -> normalizedQuery.isEmpty() || request.values().stream()
                        .anyMatch(value -> String.valueOf(value).toLowerCase(Locale.ROOT).contains(normalizedQuery)))
                .toList();
        model.addAttribute("requestList", requests);
        model.addAttribute("requestQuery", query == null ? "" : query.trim());
        model.addAttribute("requestStatus", normalizedStatus);
        model.addAttribute("supplyList", backendService.getSupplies());
        
        List<Map<String, Object>> availableUnits = backendService.getUnits().stream()
                .filter(u -> "ACTIVE".equalsIgnoreCase(String.valueOf(u.get("status"))))
                .filter(u -> unitId == null || unitId.toString().equals(String.valueOf(u.get("id"))))
                .toList();
        model.addAttribute("availableUnits", availableUnits);
        return "requests"; 
    }
    @GetMapping("/maintenance") public String maintenance(Model model) { 
        if (!model.containsAttribute("role")) return "redirect:/login";
        model.addAttribute("maintenanceList", backendService.getMaintenanceOrders()); return "maintenance"; 
    }
    @GetMapping("/my-status") public String myStatus(Model model) {
        if (!model.containsAttribute("role")) return "redirect:/login";
        Map<String, Object> profile = new java.util.HashMap<>(backendService.getMyAccessProfile());
        profile.putIfAbsent("unitCode", "UNASSIGNED");
        profile.putIfAbsent("unitName", "General HQ");
        profile.putIfAbsent("unitLocation", "Sector Command");
        profile.putIfAbsent("availabilityStatus", profile.getOrDefault("accountStatus", "ACTIVE"));
        profile.putIfAbsent("posting", "Station Headquarters");
        profile.putIfAbsent("personnelRole", profile.getOrDefault("role", "Staff"));
        profile.putIfAbsent("serviceNumber", profile.getOrDefault("username", "N/A"));
        model.addAttribute("profile", profile);
        return "my-status";
    }

    
    @GetMapping("/admin") public String adminOverview(Model model) {
        if (!model.containsAttribute("role") || !"ADMIN".equals(model.getAttribute("role"))) return "redirect:/login";
        model.addAttribute("units", backendService.getUnits().size());
        model.addAttribute("personnel", backendService.getPersonnel().size());
        model.addAttribute("vehicles", backendService.getVehicles().size());
        model.addAttribute("requests", backendService.getRequests().size());
        model.addAttribute("supplies", backendService.getSupplies().size());
        model.addAttribute("stock", backendService.getStock().size());
        model.addAttribute("accessRequests", backendService.getAccessRequests());
        model.addAttribute("pendingAccessRequests", backendService.getPendingAccessRequestCount());
        return "admin";
    }

    // --- AUTH ROUTES ---
    @GetMapping("/login") public String login() { return "login"; }
    @PostMapping("/login") public String processLogin(@RequestParam String username, @RequestParam String password, Model model, RedirectAttributes flash) {
        BackendClientService.LoginResult result = backendService.login(username, password);
        if (!result.successful()) {
            flash.addFlashAttribute("errorMessage", result.message());
            return "redirect:/login";
        }
        model.addAttribute("username", result.username());
        model.addAttribute("fullName", result.fullName());
        model.addAttribute("role", result.role());
        model.addAttribute("accessToken", result.token());
        if ("EMPLOYEE".equals(result.role())) {
            try {
                Map<String, Object> profile = backendService.getMyAccessProfile();
                Object unitId = profile.get("unitId");
                if (unitId != null) {
                    model.addAttribute("unitId", Long.valueOf(String.valueOf(unitId)));
                }
            } catch (Exception ignored) {}
        }
        return "redirect:/";
    }
    @GetMapping("/signup") public String signup() { return "signup"; }
    @PostMapping("/signup") public String processSignup(@RequestParam Map<String, String> formData, RedirectAttributes flash) {
        BackendClientService.OperationResult result = backendService.submitAccessRequest(formData);
        flash.addFlashAttribute(result.successful() ? "successMessage" : "errorMessage", result.message());
        return result.successful() ? "redirect:/access-status?username=" + UriUtils.encode(formData.get("username"), StandardCharsets.UTF_8) : "redirect:/signup";
    }
    @GetMapping("/access-status") public String accessStatus(@RequestParam(required = false) String username, Model model) {
        model.addAttribute("trackedUsername", username);
        model.addAttribute("accessRequestStatus", username == null || username.isBlank() ? List.of() : backendService.getAccessRequestStatus(username));
        return "access-status";
    }
    
    @GetMapping("/logout") public String logout(SessionStatus status) {
        status.setComplete();
        return "redirect:/login";
    }

    // --- POST ROUTES ---
    @PostMapping("/units/create") public String createUnit(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createUnit(formData), "/", flash); }
    @PostMapping("/units/{id}/status") public String updateUnitStatus(@PathVariable Long id, @RequestParam String status, @SessionAttribute(value = "role", required = false) String role, RedirectAttributes flash) {
        if (!"ADMIN".equals(role)) return forbidden("/", flash);
        return after(backendService.updateUnitStatus(id, status), "/", flash);
    }
    @PostMapping("/personnel/create") public String createPersonnel(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createPersonnel(formData), "/personnel", flash); }
    @PostMapping("/assets/create") public String createAsset(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createAsset(formData), "/assets", flash); }
    @PostMapping("/vehicles/create") public String createVehicle(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createVehicle(formData), "/fleet", flash); }
    @PostMapping("/maintenance/create") public String createMaintenance(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createMaintenanceOrder(formData), "/maintenance", flash); }
    @PostMapping("/maintenance/{id}/complete") public String completeMaintenance(@PathVariable Long id, RedirectAttributes flash) { return after(backendService.completeMaintenanceOrder(id), "/maintenance", flash); }
    @PostMapping("/personnel/{id}/availability") public String updatePersonnelAvailability(@PathVariable Long id, @RequestParam String status, RedirectAttributes flash) { return after(backendService.updatePersonnelAvailability(id, status), "/personnel", flash); }
    @PostMapping("/supplies/create") public String createSupply(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createSupply(formData), "/inventory", flash); }
    @PostMapping("/inventory/receive") public String receiveStock(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.receiveStock(formData), "/inventory", flash); }

    @PostMapping("/requests/create") public String createRequest(@RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.createRequest(formData), "/requests", flash); }
    @PostMapping("/requests/approve/{id}") public String approveRequest(@PathVariable Long id, @SessionAttribute String username, RedirectAttributes flash) { return after(backendService.approveRequest(id, username), "/requests", flash); }
    @PostMapping("/requests/reject/{id}") public String rejectRequest(@PathVariable Long id, @SessionAttribute String username, RedirectAttributes flash) { return after(backendService.rejectRequest(id, username), "/requests", flash); }
    @PostMapping("/requests/allocate/{id}") public String allocateRequest(@PathVariable Long id, @RequestParam Map<String, String> formData, RedirectAttributes flash) { return after(backendService.allocateRequest(id, formData), "/requests", flash); }
    @PostMapping("/access-requests/{id}/approve") public String approveAccessRequest(@PathVariable Long id, @SessionAttribute("role") String role, RedirectAttributes flash) {
        if (!"ADMIN".equals(role)) return forbidden("/", flash);
        return after(backendService.approveAccessRequest(id), "/admin", flash);
    }
    @PostMapping("/access-requests/{id}/reject") public String rejectAccessRequest(@PathVariable Long id, @RequestParam(required = false) String reason, @SessionAttribute("role") String role, RedirectAttributes flash) {
        if (!"ADMIN".equals(role)) return forbidden("/", flash);
        return after(backendService.rejectAccessRequest(id, reason), "/admin", flash);
    }

    private String after(BackendClientService.OperationResult result, String path, RedirectAttributes flash) {
        flash.addFlashAttribute(result.successful() ? "successMessage" : "errorMessage", result.message());
        return "redirect:" + path;
    }
    private String forbidden(String path, RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Administrator access is required for this action.");
        return "redirect:" + path;
    }

    private List<Map<String, Object>> matchingPersonnel(List<Map<String, Object>> records, String key, Long personnelId) {
        return records.stream().filter(record -> personnelId.toString().equals(String.valueOf(record.get(key)))).toList();
    }
    private boolean isRequestStatus(String status) {
        return "CREATED".equals(status) || "APPROVED".equals(status) || "REJECTED".equals(status) || "ALLOCATED".equals(status);
    }
}
