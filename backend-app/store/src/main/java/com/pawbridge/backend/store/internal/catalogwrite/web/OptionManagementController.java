package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GET remains owned by StoreCatalogController; only original option write handlers are added. */
@RestController
@RequestMapping("/api/v1/option-groups")
public final class OptionManagementController {
    private final JpaCatalogOptions options;
    public OptionManagementController(JpaCatalogOptions options){this.options=options;}
    @PostMapping public ResponseEntity<OptionGroupResponse> createOptionGroup(@RequestBody OptionGroupRequest request){
        return ResponseEntity.status(201).body(options.createOptionGroup(request));
    }
    @PutMapping("/{groupId}") public ResponseEntity<OptionGroupResponse> updateOptionGroup(
            @PathVariable("groupId") Long id,@RequestBody OptionGroupRequest request){return ResponseEntity.ok(options.updateOptionGroup(id,request));}
    @DeleteMapping("/{groupId}") public ResponseEntity<Void> deleteOptionGroup(@PathVariable("groupId") Long id){
        options.deleteOptionGroup(id);return ResponseEntity.noContent().build();
    }
    @PostMapping("/{groupId}/values") public ResponseEntity<OptionValueResponse> addOptionValue(
            @PathVariable("groupId") Long id,@RequestBody OptionValueRequest request){return ResponseEntity.status(201).body(options.addOptionValue(id,request));}
    @PutMapping("/values/{valueId}") public ResponseEntity<OptionValueResponse> updateOptionValue(
            @PathVariable("valueId") Long id,@RequestBody OptionValueRequest request){return ResponseEntity.ok(options.updateOptionValue(id,request));}
    @DeleteMapping("/values/{valueId}") public ResponseEntity<Void> deleteOptionValue(@PathVariable("valueId") Long id){
        options.deleteOptionValue(id);return ResponseEntity.noContent().build();
    }
}
