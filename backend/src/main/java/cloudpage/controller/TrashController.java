package cloudpage.controller;

import cloudpage.dto.TrashEntryDto;
import cloudpage.service.TrashService;
import cloudpage.service.UserService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for a user's trash: listing entries, restoring them, or permanently deleting them. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files/trash")
public class TrashController {

  private final TrashService trashService;
  private final UserService userService;

  @GetMapping
  public List<TrashEntryDto> listTrash() {
    return trashService.listTrash(userService.getCurrentUser().getId());
  }

  @PostMapping("/{id}/restore")
  public void restore(@PathVariable String id) throws IOException {
    var user = userService.getCurrentUser();
    trashService.restore(user.getRootFolderPath(), user.getId(), id);
  }

  @DeleteMapping("/{id}")
  public void purge(@PathVariable String id) throws IOException {
    var user = userService.getCurrentUser();
    trashService.purge(user.getRootFolderPath(), user.getId(), id);
  }
}
