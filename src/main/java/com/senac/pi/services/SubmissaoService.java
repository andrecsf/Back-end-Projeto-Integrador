package com.senac.pi.services;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.logging.Logger;

import com.senac.pi.DTO.SubmissaoDTO;
import com.senac.pi.entities.Aluno;
import com.senac.pi.entities.Categoria;
import com.senac.pi.entities.Certificado;
import com.senac.pi.entities.Submissao;
import com.senac.pi.entities.enums.StatusSubmissao;
import com.senac.pi.repositories.AlunoRepository;
import com.senac.pi.repositories.CategoriaRepository;
import com.senac.pi.repositories.SubmissaoRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class SubmissaoService {

    private static final Logger logger = Logger.getLogger(SubmissaoService.class.getName());

    @Autowired
    private EmailService emailService;

    @Autowired
    private SubmissaoRepository repository;

    @Autowired
    private AlunoRepository alunoRepository;

    @Autowired
    private CategoriaRepository categoriaRepository;

    @Autowired
    private FileService fileService;

    @Transactional(readOnly = true)
    public List<SubmissaoDTO> findAll() {
        return repository.findAll().stream().map(SubmissaoDTO::new).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SubmissaoDTO findById(Long id) {
        Submissao entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Submissão não encontrada"));
        return new SubmissaoDTO(entity);
    }

    @Transactional
    public SubmissaoDTO insert(Submissao entity, MultipartFile arquivo) {
        // 1. Validação básica do arquivo
        if (arquivo == null || arquivo.isEmpty()) {
            throw new RuntimeException("O envio do arquivo de certificado é obrigatório.");
        }

        // 2. BUSCA COMPLETA: Carrega Aluno e Categoria do banco para evitar NullPointerException
        // O JSON do Postman só envia IDs, por isso precisamos buscar os objetos reais.
        Aluno aluno = alunoRepository.findById(entity.getAluno().getId())
                .orElseThrow(() -> new EntityNotFoundException("Aluno não encontrado"));
        
        Categoria categoria = categoriaRepository.findById(entity.getCategoria().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada"));

        // 3. Validação de Regra de Negócio (Limite por Semestre)
        Instant dataInicioSemestre = Instant.now().minus(180, ChronoUnit.DAYS);

        long totalEnviado = repository.countByAlunoAndCategoriaInPeriod(
                aluno.getId(), 
                categoria.getId(), 
                dataInicioSemestre
        );

        if (totalEnviado >= categoria.getLimiteSubmissoesSemestre()) {
            throw new RuntimeException("Limite de envios atingido para a categoria: " + categoria.getArea());
        }

        // --- LÓGICA DO CERTIFICADO ---
        
        // 4. Salva o arquivo físico no disco
        String nomeArquivoNoDisco = fileService.saveFile(arquivo);

        // 5. Instancia e configura o Certificado 
        Certificado certificado = new Certificado();
        certificado.setNomeArquivo(arquivo.getOriginalFilename());
        certificado.setUrlArquivo(nomeArquivoNoDisco);
        
        // 6. Vincula o certificado à submissão 
        entity.setCertificado(certificado);
        certificado.setSubmissao(entity);

        // --- FINALIZAÇÃO DA SUBMISSÃO ---

        // 7. Atualiza a entidade com os objetos completos e dados automáticos
        entity.setAluno(aluno);
        entity.setCategoria(categoria);
        entity.setDataEnvio(Instant.now());
        entity.setStatus(StatusSubmissao.PENDENTE);
        
        // Define as horas com base na regra da categoria buscada no banco
        entity.setHorasAproveitadas(categoria.getHorasPorCertificado());

        // 8. Salva tudo (O CascadeType.ALL na Submissao salvará o Certificado)
        entity = repository.save(entity);
        return new SubmissaoDTO(entity);
    }

    @Transactional
    public SubmissaoDTO aprovar(Long id) {
        Submissao submissao = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Submissão não encontrada"));

        if (submissao.getStatus() != StatusSubmissao.PENDENTE) {
            throw new RuntimeException("Esta submissão já foi processada.");
        }

        submissao.setStatus(StatusSubmissao.APROVADO);
        
        // SOMA AS HORAS NO ALUNO
        Aluno aluno = submissao.getAluno();
        int horasAtuais = (aluno.getHorasAcumuladas() != null) ? aluno.getHorasAcumuladas() : 0;
        aluno.setHorasAcumuladas(horasAtuais + submissao.getHorasAproveitadas());
        
        alunoRepository.save(aluno);
        submissao = repository.save(submissao);

        try {
            emailService.enviarEmail(
                aluno.getEmail(),
                "SGE Senac - Certificado Aprovado ✅",
                "Olá, " + aluno.getNome() + "!\n\n" +
                "Seu certificado referente à atividade foi APROVADO.\n" +
                "Horas computadas: " + submissao.getHorasAproveitadas() + "h\n\n" +
                "Acesse o sistema para ver seu progresso.\n\n" +
                "SGE Senac"
            );
        } catch (Exception e) {
            logger.warning("Falha ao enviar e-mail de aprovação para " + aluno.getEmail() + ": " + e.getMessage());
        }

        return new SubmissaoDTO(submissao);
    }

    @Transactional
    public SubmissaoDTO rejeitar(Long id, String observacao) {
        Submissao submissao = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Submissão não encontrada"));
        
        submissao.setStatus(StatusSubmissao.REJEITADO);
        submissao.setObservacaoCoordenador(observacao);
        
        submissao = repository.save(submissao);

        Aluno aluno = submissao.getAluno();
        try {
            emailService.enviarEmail(
                aluno.getEmail(),
                "SGE Senac - Certificado Reprovado ❌",
                "Olá, " + aluno.getNome() + "!\n\n" +
                "Infelizmente seu certificado foi REPROVADO.\n\n" +
                "Motivo: " + observacao + "\n\n" +
                "Em caso de dúvidas, entre em contato com seu coordenador.\n\n" +
                "SGE Senac"
            );
        } catch (Exception e) {
            logger.warning("Falha ao enviar e-mail de reprovação para " + aluno.getEmail() + ": " + e.getMessage());
        }

        return new SubmissaoDTO(submissao);
    }
}
