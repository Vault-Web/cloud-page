# Project Structure

## backend

- 📁 **src**
  - 📁 **main**
    - 📁 **java**
      - 📁 **cloudpage**
        - 📄 [Application.java](backend/src/main/java/cloudpage/Application.java)
        - 📁 **config**
          - 📄 [CorsConfig.java](backend/src/main/java/cloudpage/config/CorsConfig.java)
          - 📄 [OpenApiConfig.java](backend/src/main/java/cloudpage/config/OpenApiConfig.java)
        - 📁 **controller**
          - 📄 [FileController.java](backend/src/main/java/cloudpage/controller/FileController.java)
          - 📄 [FolderController.java](backend/src/main/java/cloudpage/controller/FolderController.java)
        - 📁 **dto**
          - 📄 [FileDto.java](backend/src/main/java/cloudpage/dto/FileDto.java)
          - 📄 [FolderDto.java](backend/src/main/java/cloudpage/dto/FolderDto.java)
        - 📁 **model**
          - 📄 [User.java](backend/src/main/java/cloudpage/model/User.java)
        - 📁 **repository**
          - 📄 [UserRepository.java](backend/src/main/java/cloudpage/repository/UserRepository.java)
        - 📁 **security**
          - 📄 [JwtAuthFilter.java](backend/src/main/java/cloudpage/security/JwtAuthFilter.java)
          - 📄 [JwtUtil.java](backend/src/main/java/cloudpage/security/JwtUtil.java)
          - 📄 [SecurityConfig.java](backend/src/main/java/cloudpage/security/SecurityConfig.java)
        - 📁 **service**
          - 📄 [FileService.java](backend/src/main/java/cloudpage/service/FileService.java)
          - 📄 [FolderService.java](backend/src/main/java/cloudpage/service/FolderService.java)
          - 📄 [UserService.java](backend/src/main/java/cloudpage/service/UserService.java)
  - 📁 **test**
    - 📁 **java**
      - 📁 **cloudpage**
        - 📄 [ApplicationTests.java](backend/src/test/java/cloudpage/ApplicationTests.java)
