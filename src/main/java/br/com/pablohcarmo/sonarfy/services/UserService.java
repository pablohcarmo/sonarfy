package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;

    public UserService (UserRepository userRepository, PasswordEncoder passwordEncoder, PermissionRepository permissionRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionRepository = permissionRepository;
    }

    // TODO - Login do usuário, podendo ser feito tanto pelo email quanto pelo handle
    // Se quiser deixar explícito que é um processo de login,
    // criar uma classe separada chamada AuthService com um método login(), mas o loadUserByUsername precisará continuar existindo intacto aqui no UserService para alimentar o sistema.
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Caso o usuário acesse com o handle, remove o "@" do início
        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        return userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase(cleanUsername, cleanUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));
    }

    public void newUser(NewUserDto newUserDto) {
        // Limpar o handle, caso o usuário tenha digitado com "@"
        String cleanHandle = newUserDto.getHandle().startsWith("@")
                ? newUserDto.getHandle().substring(1)
                : newUserDto.getHandle();

        // Verificar se já existe um usuário com o mesmo email ou handle
        boolean userExists = userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase(
                newUserDto.getEmail(), cleanHandle
        ).isPresent();

        if (userExists) {
            throw new RuntimeException("User with this email or handle already exists!");
        }

        User user = new User();
        user.setName(newUserDto.getName());
        user.setSurname(newUserDto.getSurname());
        user.setHandle((cleanHandle));
        user.setEmail(newUserDto.getEmail());
        user.setPassword(passwordEncoder.encode(newUserDto.getPassword()));
        user.setBirthDate(newUserDto.getBirthDate());

        // Define a permissão do usuário como "ROLE_USER" por padrão
        Permission defaultPermission = new Permission();
        defaultPermission = permissionRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new RuntimeException("Default permission not found!"));
        user.setPermissionId(defaultPermission);

        user = userRepository.save(user);
    }

    public void updateProfile() {
    }

    public String sendPasswordResetEmail() {
        return null;
    }

    public String resetPassword() {
        return null;
    }

    public String updatePassword() {
        return null;
    }

    public String validatePasswordResetToken() {
        return null;
    }

    public String deleteAccount() {
        return null;
    }

    public String deactivateAccount() {
        return null;
    }

    // Verificar, pois o usuário pode reativar a conta logando novamente
    public String reactivateAccount() {
        return null;
    }

    public String changeAvatar() {
        return null;
    }

    public String changeBiography() {
        return null;
    }

    public String changeWallpaper() {
        return null;
    }

    public String findUserByHandle() {
        return null;
    }

    // TODO - verificar a necessidade de uma classe SocialService para lidar com as redes sociais,
    //  ou se isso deve ser feito aqui mesmo no UserService
    public String viewProfile() {
        return null;
    }

    public String viewFollowers() {
        return null;
    }

    public String viewFollowing() {
        return null;
    }
}